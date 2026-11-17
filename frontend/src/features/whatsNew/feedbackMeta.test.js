import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { FEEDBACK_AREAS } from './feedbackMeta.js';

// FeedbackService.java copies these labels into the reporter's email; this keeps the two lists from drifting.
const JAVA = readFileSync(
  resolve(process.cwd(), '../backend/src/main/java/th/co/glr/hr/feedback/FeedbackService.java'), 'utf8');

describe('FEEDBACK_AREAS ↔ FeedbackService', () => {
  it('every area code and label matches the backend email labels and its AREAS set', () => {
    const javaLabels = [...JAVA.matchAll(/Map\.entry\("([A-Z_]+)", "([^"]+)"\)/g)].map((m) => [m[1], m[2]]);
    expect(javaLabels).toEqual(FEEDBACK_AREAS);
    const areas = JAVA.match(/AREAS = Set\.of\(([^)]*)\)/)[1].match(/[A-Z_]+/g);
    expect([...areas].sort()).toEqual(FEEDBACK_AREAS.map(([code]) => code).sort());
  });
});
