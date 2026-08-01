const TECHNICAL_MESSAGE_MAP = [
  {
    test: (message) => /user is not linked to an employee/i.test(message),
    message: 'ยังเชื่อมบัญชีนี้กับข้อมูลพนักงานไม่ได้',
    description: 'ติดต่อฝ่ายบุคคลเพื่อเชื่อมบัญชีกับข้อมูลพนักงาน แล้วลองใหม่อีกครั้ง',
  },
];

function textFrom(value) {
  if (!value) return '';
  if (typeof value === 'string') return value;
  if (typeof value.message === 'string') return value.message;
  return '';
}

export function technicalMessageMatch(value) {
  const message = textFrom(value);
  if (!message) return null;
  return TECHNICAL_MESSAGE_MAP.find((entry) => entry.test(message)) ?? null;
}

export function toUserErrorMessage(error, fallback = 'ดำเนินการไม่สำเร็จ') {
  const mapped = technicalMessageMatch(error);
  if (mapped) return mapped.message;
  return fallback;
}

export function toUserErrorDescription(error, fallback = '') {
  const mapped = technicalMessageMatch(error);
  if (mapped) return mapped.description;
  return fallback;
}

export function sanitizeToastMessage(kind, message) {
  if (kind !== 'error') return message;
  const mapped = technicalMessageMatch(message);
  if (!mapped) return message;
  console.error('Suppressed technical error toast:', textFrom(message));
  return mapped.message;
}
