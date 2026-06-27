import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider, theme } from 'antd';
import AppLayout from './components/AppLayout';
import Dashboard from './pages/Dashboard';
import Strategies from './pages/Strategies';
import Factors from './pages/Factors';
import Risk from './pages/Risk';
import Signals from './pages/Signals';
import Backtests from './pages/Backtests';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 3000,
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

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
        <BrowserRouter>
          <Routes>
            <Route element={<AppLayout />}>
              <Route path="/" element={<Dashboard />} />
              <Route path="/strategies" element={<Strategies />} />
              <Route path="/factors" element={<Factors />} />
              <Route path="/risk" element={<Risk />} />
              <Route path="/signals" element={<Signals />} />
              <Route path="/backtests" element={<Backtests />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </ConfigProvider>
    </QueryClientProvider>
  );
}
