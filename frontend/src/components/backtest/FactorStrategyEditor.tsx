import { Button, Col, Form, Input, Row, Segmented } from 'antd';
import { PlayCircleOutlined } from '@ant-design/icons';
import type {
  BacktestMarketType,
  FactorPositionMode,
  FactorStrategySpec,
} from '../../types';
import FactorRuleGroupEditor from './FactorRuleGroupEditor';

interface FactorStrategyEditorProps {
  value: FactorStrategySpec;
  marketType: BacktestMarketType;
  factors: string[];
  running: boolean;
  onChange: (value: FactorStrategySpec) => void;
  onRun: () => void;
}

export default function FactorStrategyEditor({
  value,
  marketType,
  factors,
  running,
  onChange,
  onRun,
}: FactorStrategyEditorProps) {
  const update = (patch: Partial<FactorStrategySpec>) => onChange({ ...value, ...patch });
  const changePositionMode = (positionMode: FactorPositionMode) => {
    update(positionMode === 'LONG_SHORT'
      ? {
          positionMode,
          shortEntry: {
            mode: 'ALL', minimumMatchRatio: 1,
            rules: [{ factorName: 'RSI', operator: 'GTE', target: 'CONSTANT', threshold: 70, weight: 1 }],
          },
          shortExit: {
            mode: 'ALL', minimumMatchRatio: 1,
            rules: [{ factorName: 'RSI', operator: 'LTE', target: 'CONSTANT', threshold: 30, weight: 1 }],
          },
        }
      : { positionMode, shortEntry: undefined, shortExit: undefined });
  };

  return (
    <>
      <Row gutter={[12, 0]}>
        <Col xs={24} md={12}>
          <Form.Item label="Research strategy name">
            <Input
              maxLength={100}
              value={value.name}
              onChange={(event) => update({ name: event.target.value })}
            />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item label="Position mode">
            <Segmented<FactorPositionMode>
              value={value.positionMode}
              onChange={changePositionMode}
              options={[
                { value: 'LONG_ONLY', label: 'Long only' },
                { value: 'LONG_SHORT', label: 'Long / short', disabled: marketType === 'SPOT' },
              ]}
            />
          </Form.Item>
        </Col>
      </Row>
      <FactorRuleGroupEditor
        title="Long entry"
        value={value.longEntry}
        factors={factors}
        onChange={(longEntry) => update({ longEntry })}
      />
      <FactorRuleGroupEditor
        title="Long exit"
        value={value.longExit}
        factors={factors}
        onChange={(longExit) => update({ longExit })}
      />
      {value.positionMode === 'LONG_SHORT' && value.shortEntry && value.shortExit && (
        <>
          <FactorRuleGroupEditor
            title="Short entry"
            value={value.shortEntry}
            factors={factors}
            onChange={(shortEntry) => update({ shortEntry })}
          />
          <FactorRuleGroupEditor
            title="Short exit"
            value={value.shortExit}
            factors={factors}
            onChange={(shortExit) => update({ shortExit })}
          />
        </>
      )}
      <Button
        type="primary"
        icon={<PlayCircleOutlined />}
        loading={running}
        disabled={!value.name.trim() || factors.length === 0}
        onClick={onRun}
        style={{ marginTop: 16 }}
      >
        Run factor backtest
      </Button>
    </>
  );
}
