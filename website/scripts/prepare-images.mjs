import sharp from 'sharp';
import { fileURLToPath } from 'node:url';

for (const mode of ['day', 'night']) {
  const input = fileURLToPath(new URL(`../src/assets/positions-normal-${mode}.png`, import.meta.url));
  const output = fileURLToPath(new URL(`../src/assets/positions-crop-${mode}.webp`, import.meta.url));
  await sharp(input).resize({ width: 923 }).extract({ left: 45, top: 289, width: 832, height: 487 }).webp({ quality: 88 }).toFile(output);
}
await sharp(fileURLToPath(new URL('../src/assets/logo.png', import.meta.url)))
  .extract({ left: 144, top: 144, width: 576, height: 576 }).webp({ quality: 90 })
  .toFile(fileURLToPath(new URL('../src/assets/logo-crop.webp', import.meta.url)));
console.log('Prepared cropped Day/Night app captures (WebP).');
const cropped = await sharp(fileURLToPath(new URL('../src/assets/logo-crop.webp', import.meta.url))).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
for (let i = 0; i < cropped.data.length; i += 4) {
  if (cropped.data[i] > 235 && cropped.data[i + 1] > 235 && cropped.data[i + 2] > 235) cropped.data[i + 3] = 0;
}
await sharp(cropped.data, { raw: cropped.info }).webp({ lossless: true }).toFile(fileURLToPath(new URL('../src/assets/logo-dark.webp', import.meta.url)));
