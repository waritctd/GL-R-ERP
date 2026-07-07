import { Component } from 'react';

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
      return <DefaultErrorFallback error={error} reset={this.reset} />;
    }

    return children;
  }
}

function DefaultErrorFallback({ error, reset }) {
  return (
    <div className="panel empty-state">
      <strong>เกิดข้อผิดพลาด / Something went wrong</strong>
      <span>โหลดหน้าใหม่หรือลองอีกครั้ง</span>
      {import.meta.env.DEV && error?.message ? (
        <span>{error.message}</span>
      ) : null}
      <div className="page-actions">
        <button type="button" className="secondary-button" onClick={reset}>
          ลองใหม่ / Try again
        </button>
        <button type="button" className="primary-button" onClick={() => window.location.reload()}>
          โหลดหน้าใหม่ / Reload
        </button>
      </div>
    </div>
  );
}
