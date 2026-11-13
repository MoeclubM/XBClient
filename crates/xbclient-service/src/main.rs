fn main() {
    if let Err(err) = neto_service::run_cli() {
        eprintln!("{err:#}");
        std::process::exit(1);
    }
}
