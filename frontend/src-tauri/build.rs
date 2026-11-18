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
            "vault_exists",
            "vault_create",
            "vault_unlock",
            "vault_rewrap",
            "vault_lock",
            "vault_wipe",
            "vault_put",
            "vault_get",
            "vault_list",
            "vault_remove",
            "vault_info",
        ]),
    ))
    .expect("chyba tauri-build");
}
