#!/usr/bin/env node
// Load the emitted web guest and call its exports.
// Does not reimplement policy/admission predicates. There is no nbb host copy.
//
// Guest execution is instantiateKotoba on the kotoba compile --target web
// artifact. kotoba run on typed forms may reject or return adapter-required
// (planned); that is not treated as a guest run.

import { pathToFileURL } from "node:url";
import { existsSync } from "node:fs";
import { resolve } from "node:path";

const file = "target/kotoba/launcher.mjs";
const abs = resolve(file);
if (!existsSync(abs)) {
  console.error(`missing emitted guest ${file} — run scripts/kotoba-compile.sh first`);
  process.exit(1);
}

const mod = await import(pathToFileURL(abs).href);
if (typeof mod.instantiateKotoba !== "function") {
  console.error("launcher: no instantiateKotoba export");
  process.exit(1);
}

const guest = mod.instantiateKotoba({});
const main = guest.main();
if (main === 0n || main === 0) {
  console.error(`launcher: guest main stayed 0 (got ${main})`);
  process.exit(1);
}

const policy = guest.run("policy");
if (policy !== 11n && policy !== 11) {
  console.error(`launcher: guest run(policy) => ${policy}, expected 11`);
  process.exit(1);
}

const nativeHost = guest.run("native-host");
if (nativeHost !== 90n && nativeHost !== 90) {
  console.error(`launcher: guest run(native-host) => ${nativeHost}, expected 90 (host-listen HOLD)`);
  process.exit(1);
}

const unknown = guest.run("not-a-command");
if (unknown !== 2n && unknown !== 2) {
  console.error(`launcher: guest run(not-a-command) => ${unknown}, expected 2`);
  process.exit(1);
}

const denied = guest["policy-status"]("webauthn/register");
if (denied !== 21n && denied !== 21) {
  console.error(`launcher: guest policy-status(webauthn/register) => ${denied}, expected 21`);
  process.exit(1);
}

const allowed = guest["policy-status"]("clipboard/write-text");
if (allowed !== 11n && allowed !== 11) {
  console.error(`launcher: guest policy-status(clipboard/write-text) => ${allowed}, expected 11`);
  process.exit(1);
}

const cap = guest["capability-of"]("clipboard/write-text");
if (cap !== "clipboard/text") {
  console.error(`launcher: guest capability-of(clipboard/write-text) => ${cap}, expected clipboard/text`);
  process.exit(1);
}

const macos = guest["target-ok?"]("macos");
if (macos !== true) {
  console.error(`launcher: guest target-ok?(macos) => ${macos}, expected true`);
  process.exit(1);
}

console.log(`launcher: instantiateKotoba main=${main} run(policy)=${policy} run(native-host)=${nativeHost} policy-status(write)=${allowed} policy-status(webauthn)=${denied} capability-of(write)=${cap} target-ok?(macos)=${macos}`);
