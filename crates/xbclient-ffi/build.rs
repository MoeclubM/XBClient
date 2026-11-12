fn main() {
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        for sym in ["neto_init", "neto_call", "neto_shutdown", "neto_free_string"] {
            println!("cargo:rustc-cdylib-link-arg=/EXPORT:{sym}");
        }
    }
}
