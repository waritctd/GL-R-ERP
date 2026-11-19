// @vitest-environment node
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ESLint } from 'eslint';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import eslintConfig from '../../../eslint.config.js';
import { buttonVariants } from './Button.jsx';

const FRONTEND = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const SNIPPET_FILE = 'src/features/example/Example.jsx';
const eslint = new ESLint({ cwd: FRONTEND, overrideConfigFile: true, overrideConfig: eslintConfig });

// What the runtime guard accepts, read from its own error message, so this file holds no second
// list that could drift from Button.jsx.
function runtimeVariants() {
  try {
    buttonVariants({ variant: 'not-a-variant' });
  } catch (error) {
    const names = error.message.match(/Use one of: ([^.]+)\./);
    if (!names) throw new Error(`Button.jsx no longer ends its unknown-variant error with "Use one of: a, b, c." It said: ${error.message}`);
    return names[1].split(', ');
  }
  throw new Error('the runtime guard in Button.jsx did not throw');
}
const KNOWN = runtimeVariants();
const USE_ONE_OF = `Use one of: ${KNOWN.join(', ')}.`;

const component = (jsx) =>
  `import { Button } from '../../components/common/Button.jsx';\n\nexport function Example({ tone, busy, VARIANTS }) {\n  return ${jsx};\n}\n`;

async function lint(code, file = SNIPPET_FILE) {
  const [result] = await eslint.lintText(code, { filePath: path.join(FRONTEND, file) });
  const fatal = result.messages.find((message) => message.fatal);
  if (fatal) throw new Error(`the snippet does not parse: ${fatal.message}`);
  return result.messages.filter((message) => message.ruleId === 'no-restricted-syntax');
}
const variantReports = async (code, file) => (await lint(code, file)).filter((message) => message.message.includes('Use one of: '));

// Runs the real ESLint over small snippets with the project's own config: the lint rule reads the source, so it
// catches an unknown variant in a branch that never renders (where the runtime guard cannot).
vi.setConfig({ testTimeout: 20000 });

describe('lint: an unknown Button variant', () => {
  // Loading the React, hooks and a11y plugins takes a few seconds the first time.
  beforeAll(async () => {
    await lint(component('<Button>ok</Button>'));
  }, 60000);

  it('reports a literal unknown variant on <Button>, with the names to use', async () => {
    const reports = await variantReports(component('<Button variant="ghost">ย้อนกลับ</Button>'));

    expect(reports).toHaveLength(1);
    expect(reports[0].line).toBe(4);
    expect(reports[0].message).toContain(USE_ONE_OF);
  });

  it.each(KNOWN)('accepts variant="%s" on <Button>', async (variant) => {
    expect(await variantReports(component(`<Button variant="${variant}" aria-label="x">x</Button>`))).toHaveLength(0);
  });

  it('accepts exactly the variants the runtime guard knows, in every selector of the rule', async () => {
    const config = await eslint.calculateConfigForFile(path.join(FRONTEND, SNIPPET_FILE));
    const [, ...entries] = config.rules['no-restricted-syntax'];
    const variantEntries = entries.filter((entry) => entry.message.includes('Use one of: '));

    expect(variantEntries.length).toBeGreaterThan(0);
    for (const entry of variantEntries) {
      expect(entry.message).toContain(USE_ONE_OF);
      expect(entry.selector).toContain(`/^(${KNOWN.join('|')})$/`);
    }
  });
});
