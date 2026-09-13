#include <jni.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <cerrno>
#include <string>
#include <vector>

namespace {
std::string from_jstring(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jintArray failure(JNIEnv* env, int code) {
    jint values[2] = {-1, code};
    jintArray result = env->NewIntArray(2);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}
}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_cl_villagranquiroz_ohm_1launcher_NativePtyBridge_spawn(
    JNIEnv* env,
    jobject,
    jstring shell_value,
    jstring cwd_value,
    jobjectArray environment,
    jint rows,
    jint columns) {
    const std::string shell = from_jstring(env, shell_value);
    const std::string cwd = from_jstring(env, cwd_value);
    if (shell.empty() || rows <= 0 || columns <= 0) return failure(env, EINVAL);

    std::vector<std::string> variables;
    if (environment != nullptr) {
        const jsize count = env->GetArrayLength(environment);
        variables.reserve(static_cast<size_t>(count));
        for (jsize index = 0; index < count; ++index) {
            auto value = static_cast<jstring>(env->GetObjectArrayElement(environment, index));
            variables.push_back(from_jstring(env, value));
            env->DeleteLocalRef(value);
        }
    }

    const int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) return failure(env, errno);
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        const int error = errno;
        close(master);
        return failure(env, error);
    }
    char slave_name[128] = {};
    if (ptsname_r(master, slave_name, sizeof(slave_name)) != 0) {
        const int error = errno;
        close(master);
        return failure(env, error);
    }
    const int slave = open(slave_name, O_RDWR | O_NOCTTY);
    if (slave < 0) {
        const int error = errno;
        close(master);
        return failure(env, error);
    }

    termios attributes{};
    if (tcgetattr(slave, &attributes) == 0) {
        // Quake already renders submitted commands itself. Disabling PTY echo
        // prevents bootstrap exports and wrapped input from polluting output.
        attributes.c_lflag &= static_cast<tcflag_t>(~ECHO);
        tcsetattr(slave, TCSANOW, &attributes);
    }

    winsize size{};
    size.ws_row = static_cast<unsigned short>(rows);
    size.ws_col = static_cast<unsigned short>(columns);
    if (ioctl(slave, TIOCSWINSZ, &size) != 0) {
        const int error = errno;
        close(slave);
        close(master);
        return failure(env, error);
    }

    const pid_t pid = fork();
    if (pid < 0) {
        const int error = errno;
        close(slave);
        close(master);
        return failure(env, error);
    }
    if (pid == 0) {
        close(master);
        if (setsid() < 0 || ioctl(slave, TIOCSCTTY, 0) < 0) _exit(126);
        if (dup2(slave, STDIN_FILENO) < 0 || dup2(slave, STDOUT_FILENO) < 0 || dup2(slave, STDERR_FILENO) < 0) {
            _exit(126);
        }
        if (slave > STDERR_FILENO) close(slave);
        if (!cwd.empty() && chdir(cwd.c_str()) != 0) _exit(126);
        for (const auto& variable : variables) {
            const size_t separator = variable.find('=');
            if (separator == std::string::npos || separator == 0) continue;
            const std::string key = variable.substr(0, separator);
            const std::string value = variable.substr(separator + 1);
            setenv(key.c_str(), value.c_str(), 1);
        }
        // Keep one persistent shell without its own prompt/command echo. The
        // PTY still gives child tools a real terminal; Quake owns the prompt.
        execl(shell.c_str(), shell.c_str(), "-s", static_cast<char*>(nullptr));
        _exit(127);
    }

    close(slave);
    jint values[2] = {static_cast<jint>(pid), static_cast<jint>(master)};
    jintArray result = env->NewIntArray(2);
    if (result == nullptr) {
        kill(pid, SIGHUP);
        close(master);
        return nullptr;
    }
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_cl_villagranquiroz_ohm_1launcher_NativePtyBridge_resize(
    JNIEnv*, jobject, jint fd, jint rows, jint columns) {
    if (fd < 0 || rows <= 0 || columns <= 0) return;
    winsize size{};
    size.ws_row = static_cast<unsigned short>(rows);
    size.ws_col = static_cast<unsigned short>(columns);
    ioctl(fd, TIOCSWINSZ, &size);
}

extern "C" JNIEXPORT void JNICALL
Java_cl_villagranquiroz_ohm_1launcher_NativePtyBridge_terminate(
    JNIEnv*, jobject, jint pid) {
    if (pid <= 0) return;
    kill(pid, SIGHUP);
    int status = 0;
    waitpid(pid, &status, WNOHANG);
}
