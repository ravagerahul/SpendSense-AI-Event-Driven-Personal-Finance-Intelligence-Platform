import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { DashboardPage } from './pages/DashboardPage';
import { TransactionsPage } from './pages/TransactionsPage';
import { ChatPage } from './pages/ChatPage';
import { ReviewPage } from './pages/ReviewPage';
import { AnomaliesPage } from './pages/AnomaliesPage';
import { InsightsPage } from './pages/InsightsPage';

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<DashboardPage />} />
          <Route path="transactions" element={<TransactionsPage />} />
          <Route path="chat" element={<ChatPage />} />
          <Route path="review" element={<ReviewPage />} />
          <Route path="anomalies" element={<AnomaliesPage />} />
          <Route path="insights" element={<InsightsPage />} />
          {/* A mistyped URL lands on the dashboard rather than a blank screen. */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
