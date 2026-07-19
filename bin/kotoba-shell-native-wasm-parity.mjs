#!/usr/bin/env node
import { readFile } from 'node:fs/promises';

const [wasm, expectedRaw = '31'] = process.argv.slice(2);
if (!wasm) {
  console.error('usage: kotoba-shell-native-wasm-parity.mjs <guest.wasm> [expected-main]');
  process.exit(64);
}
const bytes = await readFile(wasm);
const { instance } = await WebAssembly.instantiate(bytes, {});
const actual = Number(instance.exports.main());
const expected = Number(expectedRaw);
if (actual !== expected) {
  console.error(JSON.stringify({event:'native-wasm/mismatch', wasm, expected, actual}));
  process.exit(1);
}
console.log(JSON.stringify({event:'native-wasm/parity', wasm, expected, actual, imports:Object.keys(instance.module ? {} : {})}));
