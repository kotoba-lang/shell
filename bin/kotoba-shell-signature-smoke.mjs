#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { createHash, generateKeyPairSync, sign, verify } from 'node:crypto';

const artifact = process.argv[2];
if (!artifact) {
  console.error('usage: kotoba-shell-signature-smoke.mjs <artifact>');
  process.exit(64);
}
const bytes = await readFile(artifact);
const digest = createHash('sha256').update(bytes).digest();
const { privateKey, publicKey } = generateKeyPairSync('ed25519');
const signature = sign(null, digest, privateKey);
const valid = verify(null, digest, publicKey, signature);
const tampered = Buffer.from(digest);
tampered[0] ^= 1;
const rejectsTamper = !verify(null, tampered, publicKey, signature);
if (!valid || !rejectsTamper) process.exit(1);
console.log(JSON.stringify({event:'signature/verified', algorithm:'ed25519', digest:digest.toString('hex'), tamperRejected:rejectsTamper}));
