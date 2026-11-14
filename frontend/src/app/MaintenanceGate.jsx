import React from 'react';
import { MAINTENANCE } from './features.js';

// Replaces the entire app while MAINTENANCE is on. Inline styles on purpose: it
// must not depend on Tailwind/CSS chunks or any API-backed component.
export function MaintenanceGate({ children }) {
  if (!MAINTENANCE) return children;
  return (
    <main
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '24px',
        textAlign: 'center',
        fontFamily: "'Sarabun', system-ui, sans-serif",
        background: '#f6f7f9',
        color: '#1f2937',
      }}
    >
      <div style={{ maxWidth: 480 }}>
        <div style={{ fontSize: 56 }} aria-hidden="true">🚧</div>
        <h1 style={{ fontSize: 28, margin: '12px 0 8px' }}>ปรับปรุงระบบชั่วคราว</h1>
        <p style={{ fontSize: 16, lineHeight: 1.6, margin: 0 }}>
          ขณะนี้ GL&amp;R Portal กำลังย้ายไปยังเซิร์ฟเวอร์ใหม่ ขออภัยในความไม่สะดวก
          กรุณากลับมาใช้งานใหม่อีกครั้งในภายหลัง
        </p>
        <p style={{ fontSize: 14, lineHeight: 1.6, margin: '16px 0 0', color: '#6b7280' }}>
          GL&amp;R Portal is being migrated to new servers and is temporarily unavailable.
          Please check back shortly.
        </p>
      </div>
    </main>
  );
}
