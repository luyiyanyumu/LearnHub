import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { pipeline } from 'node:stream/promises';

// No host shell: paths, ports and user values remain individual arguments.
export async function runDocker(args, { env = {}, cwd, outputFile, inherit = false } = {}) {
  const child = spawn('docker', args, {
    cwd, env: { ...process.env, ...env }, shell: false, windowsHide: true,
    stdio: ['ignore', outputFile || !inherit ? 'pipe' : 'inherit', inherit ? 'inherit' : 'pipe'],
  });
  let stdout = '';
  let stderr = '';
  if (!outputFile && child.stdout) child.stdout.on('data', chunk => { stdout += chunk; });
  if (child.stderr) child.stderr.on('data', chunk => { stderr = (stderr + chunk).slice(-64 * 1024); });
  const completion = new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('close', code => {
      if (code === 0) resolve();
      else reject(new Error(`Docker 命令失败（${code ?? 'signal'}）：${stderr.trim() || args.slice(0, 3).join(' ')}`));
    });
  });
  const output = outputFile ? pipeline(child.stdout, createWriteStream(outputFile, { flags: 'wx', mode: 0o600 })) : Promise.resolve();
  try {
    await Promise.all([completion, output]);
  } catch (error) {
    child.kill();
    throw error;
  }
  return { stdout, stderr };
}

export function openBrowser(url) {
  // URLs are generated locally; no command interpolation or shell is used.
  const command = process.platform === 'win32' ? 'rundll32.exe' : process.platform === 'darwin' ? 'open' : 'xdg-open';
  const args = process.platform === 'win32' ? ['url.dll,FileProtocolHandler', url] : [url];
  const child = spawn(command, args, { detached: true, stdio: 'ignore', windowsHide: true });
  child.on('error', () => {});
  child.unref();
}
