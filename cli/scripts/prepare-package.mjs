import { copyFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const packageRoot = new URL('../', import.meta.url);
await mkdir(new URL('templates/', packageRoot), { recursive: true });
await copyFile(new URL('../deploy/docker-compose.release.yml', packageRoot), new URL('templates/compose.yml', packageRoot));
await copyFile(new URL('../LICENSE', packageRoot), new URL('LICENSE', packageRoot));
console.log(`Prepared ${fileURLToPath(packageRoot)}`);
