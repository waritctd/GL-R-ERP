import { Component } from 'react';
import { StatePanel } from './StatePanel.jsx';

export class ErrorBoundary extends Component {
  state = { error: null };

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    console.error('UI ErrorBoundary caught:', error, info);
    // A future Sentry/error-reporting hook attaches here — no dependency added yet.
  }

  reset = () => {
    this.setState({ error: null });
  };

  render() {
    const { error } = this.state;
    const { fallback, children } = this.props;

    if (error) {
      if (typeof fallback === 'function') {
        return fallback({ error, reset: this.reset });
      }
      return <DefaultErrorFallback reset={this.reset} />;
    }

    return children;
  }
}

function DefaultErrorFallback({ reset }) {
  return (
    <StatePanel
      state="error"
      title="โหลดหน้านี้ไม่สำเร็จ"
      description="ลองใหม่อีกครั้ง หรือโหลดหน้าใหม่หากข้อมูลยังไม่แสดง"
      action={(
        <button type="button" className="secondary-button" onClick={reset}>
          ลองใหม่
        </button>
      )}
      secondaryAction={(
        <button type="button" className="primary-button" onClick={() => window.location.reload()}>
          โหลดหน้าใหม่
        </button>
      )}
    />
  );
}
