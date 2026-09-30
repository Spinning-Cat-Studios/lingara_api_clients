//! The generator view's CLI; everything it does is in `spec_codegen::cli`.

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    std::process::exit(spec_codegen::cli::run(&args));
}
