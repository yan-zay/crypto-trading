import { lazy, Suspense, type ReactNode } from 'react';
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp, ConfigProvider, theme, Spin } from 'antd';
import AppLayout from './components/AppLayout';
import { AuthProvider } from './auth/AuthContext';
import { useAuth } from './auth/useAuth';
import { notify } from './feedback/notify';
import { NotificationBridge } from './feedback/NotificationBridge';

// ── 代码分割：页面懒加载 ───────────────────────────────────────
const Dashboard = lazy(() => import('./pages/Dashboard'));
const Strategies = lazy(() => import('./pages/Strategies'));
const Factors = lazy(() => import('./pages/Factors'));
const Risk = lazy(() => import('./pages/Risk'));
const Signals = lazy(() => import('./pages/Signals'));
const Orders = lazy(() => import('./pages/Orders'));
const Backtests = lazy(() => import('./pages/Backtests'));
const BacktestResult = lazy(() => import('./pages/BacktestResult'));
const BacktestJobs = lazy(() => import('./pages/BacktestJobs'));
const Reliability = lazy(() => import('./pages/Reliability'));
const Configs = lazy(() => import('./pages/Configs'));
const Login = lazy(() => import('./pages/Login'));
const NotFound = lazy(() => import('./pages/NotFound'));

// ── 路由守卫 ───────────────────────────────────────────────────
function RequireAuth({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }
  return <>{children}</>;
}

function RequireGuest({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAuth();

  if (isAuthenticated) {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
}

// ── 全局错误处理 ───────────────────────────────────────────────
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5000,
      retry: 1,
      refetchOnWindowFocus: false,
    },
    mutations: {
      onError: (error: unknown) => {
        const msg = error instanceof Error ? error.message : '操作失败';
        notify.error(msg);
      },
    },
  },
});

// ── Suspense fallback ──────────────────────────────────────────
function PageLoading() {
  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%', minHeight: 400 }}>
      <Spin size="large" />
    </div>
  );
}

// ── App ────────────────────────────────────────────────────────
export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ConfigProvider
        theme={{
          algorithm: theme.defaultAlgorithm,
          token: {
            colorPrimary: '#1677ff',
            borderRadius: 6,
            fontSize: 13,
          },
        }}
      >
        <AntdApp>
          <NotificationBridge />
          <AuthProvider>
            <BrowserRouter>
              <Routes>
              {/* 登录页（未登录可访问） */}
              <Route
                path="/login"
                element={
                  <RequireGuest>
                    <Suspense fallback={<PageLoading />}>
                      <Login />
                    </Suspense>
                  </RequireGuest>
                }
              />

              {/* 受保护的页面 */}
              <Route
                element={
                  <RequireAuth>
                    <AppLayout />
                  </RequireAuth>
                }
              >
                <Route index element={<Suspense fallback={<PageLoading />}><Dashboard /></Suspense>} />
                <Route path="/strategies" element={<Suspense fallback={<PageLoading />}><Strategies /></Suspense>} />
                <Route path="/factors" element={<Suspense fallback={<PageLoading />}><Factors /></Suspense>} />
                <Route path="/risk" element={<Suspense fallback={<PageLoading />}><Risk /></Suspense>} />
                <Route path="/signals" element={<Suspense fallback={<PageLoading />}><Signals /></Suspense>} />
                <Route path="/orders" element={<Suspense fallback={<PageLoading />}><Orders /></Suspense>} />
                <Route path="/backtests" element={<Suspense fallback={<PageLoading />}><Backtests /></Suspense>} />
                <Route path="/backtest-results" element={<Suspense fallback={<PageLoading />}><BacktestResult /></Suspense>} />
                <Route path="/backtest-jobs" element={<Suspense fallback={<PageLoading />}><BacktestJobs /></Suspense>} />
                <Route path="/reliability" element={<Suspense fallback={<PageLoading />}><Reliability /></Suspense>} />
                <Route path="/configs" element={<Suspense fallback={<PageLoading />}><Configs /></Suspense>} />
              </Route>

              {/* 404 */}
              <Route path="*" element={<Suspense fallback={<PageLoading />}><NotFound /></Suspense>} />
              </Routes>
            </BrowserRouter>
          </AuthProvider>
        </AntdApp>
      </ConfigProvider>
    </QueryClientProvider>
  );
}
