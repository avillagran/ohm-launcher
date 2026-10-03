# Flux control-link interoperability checks

`helper.go` uses the selected Flux checkout's real `proto` and `lan.Provider`
implementations. It binds loopback sockets and uses disposable software
certificates. It neither starts fluxd nor reads a user's Flux identity.

Build each peer against the intended source, keeping the Flux checkout intact:

```sh
python3 tools/flux/interoperability/build-helper.py --flux-repo "$FLUX_NATIVE_SOURCE" --output "$FLUX_TEST_DIR/native-helper"
python3 tools/flux/interoperability/build-helper.py --flux-repo "$FLUX_LEGACY_SOURCE" --output "$FLUX_TEST_DIR/legacy-helper"
"$FLUX_TEST_DIR/native-helper" --mode fixture --dialect native --fixtures "$FLUX_TEST_DIR/fixtures"
"$FLUX_TEST_DIR/legacy-helper" --mode fixture --dialect legacy --fixtures "$FLUX_TEST_DIR/fixtures"
```

The helper imports Flux's internal packages inside a temporary copy of the
Go module. Go must be on PATH, or supplied using `--go`. Native fixtures were
produced from the integration candidate based on upstream `1605304`; legacy
fixtures were produced from the installed integration based on `5373b74`.
The checked-in JSON fixtures contain public SPKI and identity data only.
Private test keys remain in the disposable fixture directory.

Enable the four JVM checks in addition to the regular unit suite:

```sh
OHM_FLUX_INTEROP_HELPER_NATIVE="$FLUX_TEST_DIR/native-helper" \
OHM_FLUX_INTEROP_HELPER_LEGACY="$FLUX_TEST_DIR/legacy-helper" \
OHM_FLUX_INTEROP_FIXTURES="$FLUX_TEST_DIR/fixtures" \
./gradlew :app:testDebugUnitTest --tests '*FluxGoSessionInteropTest' --no-daemon --max-workers=1
```

The tests copy the fixture directory into a temporary directory, authenticate
both certificates over TLS 1.2, exercise TCP dialing and responding in both
dialects, require phone confirmation before storing trust, and exchange share
packets in both directions. They stop the test peer after each case. These
checks cover the transport; the helper's explicit pair response does not
replace Flux core's separate user-approval tests.
