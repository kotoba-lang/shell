(ns shell
  "Zero-dep portable CLJC. Restored from the legacy kotoba-lang/kotoba
  crates/kotoba-shell Rust crate (deleted in kotoba-lang/kotoba PR #259, 2026-07-01,
  along with the rest of the legacy Rust workspace) as part of the kotoba-shell CLJC
  restoration (ADR-2607012115, com-junkawasaki/root). Native execution (WKWebView /
  Xcode / Gradle packaging) stays substrate; this namespace owns the CLJC contracts /
  manifest parsing / ShellPlan capability model for manimani's app-shell replacement
  for Tauri.")
