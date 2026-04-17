import React from 'react'
import ReactDOM from 'react-dom/client'
import './index.css'
import App from './App'

class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props)
    this.state = { error: null }
  }
  static getDerivedStateFromError(error) {
    return { error }
  }
  render() {
    if (this.state.error) {
      return (
        <div style={{ padding: '40px', color: '#fff', fontFamily: 'monospace' }}>
          <h2>Something went wrong</h2>
          <pre style={{ opacity: 0.6, fontSize: '12px' }}>{this.state.error.message}</pre>
          <button onClick={() => window.location.reload()} style={{ marginTop: '16px', padding: '8px 16px', cursor: 'pointer' }}>
            Reload
          </button>
        </div>
      )
    }
    return this.props.children
  }
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </React.StrictMode>
)
