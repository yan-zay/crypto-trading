import { Badge, Button, Flex, Input, InputNumber, Select, Space, Statistic, Typography } from 'antd';
import { PauseCircleOutlined, PlayCircleOutlined, RedoOutlined } from '@ant-design/icons';
import { useState } from 'react';
import type { PaperAccount, PaperTradingStatus } from '../../types';

interface Props {
  accounts: PaperAccount[];
  accountId?: string;
  status?: PaperTradingStatus;
  busy: boolean;
  onSelect: (accountId: string) => void;
  onStart: (balance: number, name?: string) => void;
  onStop: () => void;
  onResume: () => void;
}

const money = (value?: number | null) => value == null
  ? '-'
  : value.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export default function PaperAccountBar({
  accounts, accountId, status, busy, onSelect, onStart, onStop, onResume,
}: Props) {
  const [initialBalance, setInitialBalance] = useState(10_000);
  const [accountName, setAccountName] = useState('Research');
  const stopped = status?.account?.status === 'STOPPED';

  return (
    <div style={{ borderBottom: '1px solid #f0f0f0', paddingBottom: 16 }}>
      <Flex justify="space-between" align="center" gap={12} wrap>
        <Space wrap>
          <Badge
            status={status?.running ? 'processing' : 'default'}
            text={status?.running ? 'Paper running' : 'Paper stopped'}
          />
          <Select
            aria-label="Paper account"
            value={accountId}
            placeholder="Select account"
            style={{ width: 220 }}
            options={accounts.map((account) => ({
              value: account.accountId,
              label: `${account.accountName} · ${account.status}`,
            }))}
            onChange={onSelect}
          />
          <Input
            aria-label="Account name"
            value={accountName}
            onChange={(event) => setAccountName(event.target.value)}
            style={{ width: 130 }}
          />
          <InputNumber
            aria-label="Initial balance"
            value={initialBalance}
            min={100}
            step={1000}
            prefix="$"
            style={{ width: 140 }}
            onChange={(value) => setInitialBalance(value ?? 10_000)}
          />
          <Button
            type="primary"
            icon={<PlayCircleOutlined />}
            loading={busy}
            disabled={Boolean(status?.running)}
            onClick={() => onStart(initialBalance, accountName || undefined)}
          >
            New account
          </Button>
          {stopped && accountId && (
            <Button icon={<RedoOutlined />} loading={busy} onClick={onResume}>Resume</Button>
          )}
          <Button
            icon={<PauseCircleOutlined />}
            loading={busy}
            disabled={!status?.running}
            onClick={onStop}
          >
            Stop
          </Button>
        </Space>
        {accountId && <Typography.Text code copyable>{accountId}</Typography.Text>}
      </Flex>

      <Flex gap={28} wrap style={{ marginTop: 16 }}>
        <Statistic title="Equity" value={money(status?.equity)} prefix="$" />
        <Statistic title="Available" value={money(status?.balance)} prefix="$" />
        <Statistic
          title="Net PnL"
          value={money(status?.netPnl)}
          prefix="$"
          styles={{ content: { color: (status?.netPnl ?? 0) >= 0 ? '#237804' : '#a8071a' } }}
        />
        <Statistic title="Realized" value={money(status?.realizedPnl)} prefix="$" />
        <Statistic title="Unrealized" value={money(status?.unrealizedPnl)} prefix="$" />
        <Statistic title="Fees" value={money(status?.feesPaid)} prefix="$" />
        <Statistic title="Trades" value={status?.tradeCount ?? 0} />
        <Statistic title="Active orders" value={status?.activeOrderCount ?? 0} />
      </Flex>
    </div>
  );
}
