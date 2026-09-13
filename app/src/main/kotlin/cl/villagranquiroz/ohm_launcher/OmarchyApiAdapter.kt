package cl.villagranquiroz.ohm_launcher

/** Application-facing implementation of the transport-neutral Omarchy REST contract. */
interface OmarchyApiAdapter {
    fun handle(request: OmarchyRestRequest): OmarchyApiResponse

    fun uploadFile(name: String, bytes: ByteArray): OmarchyApiResponse =
        OmarchyApiResponse.unsupported("file")

    fun downloadFile(path: String): OmarchyFileDownload? = null
}

/** Binary file returned by `GET /omarchy/file`. */
data class OmarchyFileDownload(
    val bytes: ByteArray,
    val contentType: String = "application/octet-stream",
    val fileName: String? = null,
)
