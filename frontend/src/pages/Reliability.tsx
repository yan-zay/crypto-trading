import { Tabs, Typography } from 'antd';
import AuditPanel from '../components/reliability/AuditPanel';
import OutboxPanel from '../components/reliability/OutboxPanel';
import ReconciliationPanel from '../components/reliability/ReconciliationPanel';
import SloPanel from '../components/reliability/SloPanel';

export default function Reliability() {
  return (
    <div>
      <Typography.Title level={4}>Reliability & Audit</Typography.Title>
      <Tabs
        items={[
          { key: 'slo', label: 'SLO & Error Budget', children: <SloPanel /> },
          { key: 'audit', label: 'Audit Chain', children: <AuditPanel /> },
          { key: 'reconciliation', label: 'Reconciliation', children: <ReconciliationPanel /> },
          { key: 'outbox', label: 'Event Outbox', children: <OutboxPanel /> },
        ]}
      />
    </div>
  );
}
