// @vitest-environment node
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ESLint } from 'eslint';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import eslintConfig from '../../../eslint.config.js';
import { buttonVariants } from './Button.jsx';

// The guard in Button.jsx throws on an unknown variant, but only when the element renders. A typo
// in a branch that no test and no dev session visits still ships, and the untaken side of a
// ternary is exactly such a branch. The lint rule reads the source instead, so it sees a variant
// written at the call site whether or not it ever renders. eslint.config.js lists what it cannot see.
//
// These tests run the real ESLint over small snippets, with the project's own config. A selector
// cannot be shown wrong any other way: one that matches nothing is silent. The config is imported
// here, and not only found on disk by ESLint, so that CI's changed-files run picks this file up
// when eslint.config.js is the only thing that changed.

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

  it.each([
    ["variant={'ghost'}", 'a string in braces'],
    ["variant={busy ? 'primary' : 'ghost'}", 'the second branch of a ternary'],
    ["variant={busy ? 'ghost' : 'primary'}", 'the first branch of a ternary'],
    ["variant={tone ?? 'ghost'}", 'the fallback after ??'],
    ["variant={tone || 'ghost'}", 'the fallback after ||'],
    ["variant={busy && 'ghost'}", 'the value after &&'],
  ])('reports %s: %s', async (attribute) => {
    expect(await variantReports(component(`<Button ${attribute}>x</Button>`))).toHaveLength(1);
  });

  it.each(KNOWN)('accepts variant="%s" on <Button>', async (variant) => {
    expect(await variantReports(component(`<Button variant="${variant}" aria-label="x">x</Button>`))).toHaveLength(0);
  });

  it.each([
    ['variant={tone}'],
    ['variant={tone.variant}'],
    ['variant={VARIANTS[tone]}'],
    ["variant={busy ? 'primary' : 'secondary'}"],
    ["variant={tone ?? 'secondary'}"],
    // A string that is compared, not passed: `compact` is not a variant and is not used as one.
    ["variant={tone === 'compact' ? 'text' : 'secondary'}"],
  ])('does not report %s', async (attribute) => {
    expect(await variantReports(component(`<Button ${attribute}>x</Button>`))).toHaveLength(0);
  });

  it.each([
    ['<Badge variant="ghost">x</Badge>'],
    ['<StatusChip variant="soft" />'],
    ['<IconButton variant="ghost" />'],
    // The unknown variant sits inside the Button's opening tag but belongs to the Badge.
    ['<Button variant="primary" icon={<Badge variant="ghost" />}>x</Button>'],
  ])('leaves another component\'s variant alone: %s', async (jsx) => {
    expect(await variantReports(component(jsx))).toHaveLength(0);
  });

  it('reports an unknown variant in a direct buttonVariants call', async () => {
    const reports = await variantReports("import { buttonVariants } from '../../components/common/Button.jsx';\n\nexport const linkClass = buttonVariants({ variant: 'ghost' });\n");

    expect(reports).toHaveLength(1);
    expect(reports[0].line).toBe(3);
    expect(reports[0].message).toContain(USE_ONE_OF);
  });

  it.each(KNOWN)("accepts buttonVariants({ variant: '%s', size: 'sm' })", async (variant) => {
    const code = `import { buttonVariants } from '../../components/common/Button.jsx';\n\nexport const linkClass = buttonVariants({ variant: '${variant}', size: 'sm' });\n`;
    expect(await variantReports(code)).toHaveLength(0);
  });

  it.each([
    ["export const a = somethingElse({ variant: 'ghost' });"],
    ["export const b = buttonVariants({ size: 'ghost' });"],
    ['export const c = (options) => buttonVariants(options);'],
  ])('does not report %s', async (line) => {
    const code = `import { buttonVariants } from '../../components/common/Button.jsx';\n\nconst somethingElse = (value) => value;\n${line}\nexport const used = [buttonVariants, somethingElse];\n`;
    expect(await variantReports(code)).toHaveLength(0);
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

  // Assembled from two pieces so that this file does not itself contain the forbidden class.
  const exclusiveBreakpoint = `${'max-'}${'[720px]:hidden'}`;
  const rawFormWithExclusiveBreakpoint = component(`<form className="${exclusiveBreakpoint}"><Button>ok</Button></form>`);

  it('still applies in SafeForm.jsx, whose exemption drops the raw-form rule only', async () => {
    const file = 'src/components/common/SafeForm.jsx';
    expect(await variantReports(component('<Button variant="ghost">x</Button>'), file)).toHaveLength(1);

    const others = await lint(rawFormWithExclusiveBreakpoint, file);
    expect(others.some((message) => message.message.includes('SafeForm'))).toBe(false);
    expect(others.some((message) => message.message.includes('EXCLUSIVE'))).toBe(true);
  });

  it.each([
    ['src/components/common/Button.test.jsx'],
    ['src/components/common/Button.lint.test.js'],
  ])('lets %s write an unknown variant, and keeps the other guards on in that file', async (file) => {
    expect(await variantReports(component('<Button variant="ghost">x</Button>'), file)).toHaveLength(0);

    const others = await lint(rawFormWithExclusiveBreakpoint, file);
    expect(others.some((message) => message.message.includes('SafeForm'))).toBe(true);
    expect(others.some((message) => message.message.includes('EXCLUSIVE'))).toBe(true);
  });

  it.each([
    ['src/components/common/Button.jsx'],
    ['src/components/common/ButtonGroup.test.jsx'],
    ['src/components/common/ConfirmDialog.test.jsx'],
    ['src/features/tickets/DealStagePanel.test.jsx'],
  ])('exempts no other file: %s', async (file) => {
    expect(await variantReports(component('<Button variant="ghost">x</Button>'), file)).toHaveLength(1);
  });
});
