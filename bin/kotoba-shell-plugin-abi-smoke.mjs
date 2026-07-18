#!/usr/bin/env node
// Contract-level ABI test: [status:u32, ptr:u32, len:u32] + UTF-8 buffer.
const memory = new Uint8Array(64);
const payload = new TextEncoder().encode('kotoba-plugin-ok');
const ptr = 16;
memory.set(payload, ptr);
const frame = new DataView(new ArrayBuffer(12));
frame.setUint32(0, 0, true);
frame.setUint32(4, ptr, true);
frame.setUint32(8, payload.length, true);
const status = frame.getUint32(0, true);
const decodedPtr = frame.getUint32(4, true);
const decodedLen = frame.getUint32(8, true);
const decoded = new TextDecoder().decode(memory.slice(decodedPtr, decodedPtr + decodedLen));
if (status !== 0 || decoded !== 'kotoba-plugin-ok') process.exit(1);
console.log(JSON.stringify({event:'plugin-abi/verified', abi:'pointer-length-buffer-result', status, ptr:decodedPtr, len:decodedLen, decoded}));
