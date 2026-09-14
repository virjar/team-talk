// 从管理台现有锁定依赖生成首页专用二维码算法，不带入 React 或管理台应用。
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const admin = fileURLToPath(new URL('../../server/admin/', import.meta.url));
const require = createRequire(admin + 'package.json');
const { build } = require('esbuild');
const license = readFileSync(new URL('./home-qrcode-LICENSE.txt', import.meta.url), 'utf8');

await build({
    stdin: {
        contents: `import { QrCode, Ecc } from '@rc-component/qrcode/es/libs/qrcodegen.js';
export function encode(text) { return QrCode.encodeText(text, Ecc.MEDIUM); }`,
        resolveDir: admin,
        sourcefile: 'home-qrcode-entry.js',
    },
    bundle: true,
    format: 'iife',
    globalName: 'TeamTalkQr',
    target: 'es2020',
    minify: true,
    legalComments: 'none',
    banner: { js: '/*!\n' + license.trimEnd() + '\n*/' },
    outfile: fileURLToPath(new URL('../../server/server/src/main/resources/static/js/qrcode.min.js', import.meta.url)),
});
