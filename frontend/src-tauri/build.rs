fn main() {
    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(
        tauri_build::AppManifest::new().commands(&[
            "http_request",
            "live_connect",
            "live_disconnect",
            "config_get",
            "config_set_server_url",
            "config_device_id",
            "app_version",
        ]),
    ))
    .expect("chyba tauri-build");
}
