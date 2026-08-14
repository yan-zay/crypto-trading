import {
  Button,
  Col,
  Divider,
  InputNumber,
  Row,
  Segmented,
  Select,
  Space,
  Tooltip,
  Typography,
} from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import type {
  FactorComparisonTarget,
  FactorMatchMode,
  FactorOperator,
  FactorRule,
  FactorRuleGroup,
} from '../../types';

interface FactorRuleGroupEditorProps {
  title: string;
  value: FactorRuleGroup;
  factors: string[];
  onChange: (value: FactorRuleGroup) => void;
}

const operatorOptions: Array<{ value: FactorOperator; label: string }> = [
  { value: 'LT', label: '<' },
  { value: 'LTE', label: '<=' },
  { value: 'GT', label: '>' },
  { value: 'GTE', label: '>=' },
  { value: 'CROSS_ABOVE', label: 'Crosses above' },
  { value: 'CROSS_BELOW', label: 'Crosses below' },
];

const targetOptions: Array<{ value: FactorComparisonTarget; label: string }> = [
  { value: 'CONSTANT', label: 'Constant' },
  { value: 'PRICE', label: 'Close price' },
  { value: 'FACTOR', label: 'Another factor' },
];

function defaultRule(factors: string[]): FactorRule {
  return {
    factorName: factors[0] ?? 'RSI',
    operator: 'LTE',
    target: 'CONSTANT',
    threshold: 30,
    weight: 1,
  };
}

export default function FactorRuleGroupEditor({
  title,
  value,
  factors,
  onChange,
}: FactorRuleGroupEditorProps) {
  const updateRule = (index: number, patch: Partial<FactorRule>) => {
    const rules = value.rules.map((rule, ruleIndex) =>
      ruleIndex === index ? { ...rule, ...patch } : rule,
    );
    onChange({ ...value, rules });
  };

  const removeRule = (index: number) => {
    onChange({ ...value, rules: value.rules.filter((_, ruleIndex) => ruleIndex !== index) });
  };

  return (
    <section>
      <Divider titlePlacement="start" plain style={{ margin: '14px 0 12px' }}>
        {title}
      </Divider>
      <Space wrap size="middle" style={{ marginBottom: 12 }}>
        <Typography.Text type="secondary">Rule matching</Typography.Text>
        <Segmented<FactorMatchMode>
          value={value.mode}
          onChange={(mode) => onChange({ ...value, mode })}
          options={[
            { value: 'ALL', label: 'All' },
            { value: 'ANY', label: 'Any' },
            { value: 'WEIGHTED', label: 'Weighted' },
          ]}
        />
        {value.mode === 'WEIGHTED' && (
          <InputNumber
            aria-label={`${title} minimum weighted ratio`}
            min={0}
            max={1}
            step={0.05}
            value={value.minimumMatchRatio}
            onChange={(minimumMatchRatio) => onChange({
              ...value,
              minimumMatchRatio: minimumMatchRatio ?? 1,
            })}
            addonBefore="Minimum ratio"
            style={{ width: 190 }}
          />
        )}
      </Space>

      <Space orientation="vertical" size={8} style={{ width: '100%' }}>
        {value.rules.map((rule, index) => (
          <Row key={`${title}-${index}`} gutter={[8, 8]} align="middle" wrap>
            <Col xs={24} md={5}>
              <Select
                aria-label={`${title} factor ${index + 1}`}
                value={rule.factorName}
                onChange={(factorName) => updateRule(index, { factorName })}
                options={factors.map((factor) => ({ value: factor, label: factor }))}
                showSearch
                style={{ width: '100%' }}
              />
            </Col>
            <Col xs={12} md={4}>
              <Select
                aria-label={`${title} operator ${index + 1}`}
                value={rule.operator}
                onChange={(operator) => updateRule(index, { operator })}
                options={operatorOptions}
                style={{ width: '100%' }}
              />
            </Col>
            <Col xs={12} md={4}>
              <Select
                aria-label={`${title} comparison target ${index + 1}`}
                value={rule.target}
                onChange={(target) => updateRule(index, {
                  target,
                  threshold: target === 'CONSTANT' ? rule.threshold ?? 0 : undefined,
                  targetFactorName: target === 'FACTOR'
                    ? rule.targetFactorName ?? factors[0]
                    : undefined,
                })}
                options={targetOptions}
                style={{ width: '100%' }}
              />
            </Col>
            <Col xs={20} md={value.mode === 'WEIGHTED' ? 4 : 6}>
              {rule.target === 'CONSTANT' && (
                <InputNumber
                  aria-label={`${title} threshold ${index + 1}`}
                  value={rule.threshold}
                  onChange={(threshold) => updateRule(index, { threshold: threshold ?? 0 })}
                  placeholder="Threshold"
                  style={{ width: '100%' }}
                />
              )}
              {rule.target === 'FACTOR' && (
                <Select
                  aria-label={`${title} target factor ${index + 1}`}
                  value={rule.targetFactorName}
                  onChange={(targetFactorName) => updateRule(index, { targetFactorName })}
                  options={factors.map((factor) => ({ value: factor, label: factor }))}
                  showSearch
                  style={{ width: '100%' }}
                />
              )}
              {rule.target === 'PRICE' && (
                <Typography.Text type="secondary">Current bar close</Typography.Text>
              )}
            </Col>
            {value.mode === 'WEIGHTED' && (
              <Col xs={20} md={2}>
                <InputNumber
                  aria-label={`${title} weight ${index + 1}`}
                  min={0.01}
                  step={0.1}
                  value={rule.weight}
                  onChange={(weight) => updateRule(index, { weight: weight ?? 1 })}
                  placeholder="Weight"
                  style={{ width: '100%' }}
                />
              </Col>
            )}
            <Col xs={4} md={1}>
              <Tooltip title="Delete rule">
                <Button
                  aria-label={`Delete ${title} rule ${index + 1}`}
                  type="text"
                  danger
                  icon={<DeleteOutlined />}
                  disabled={value.rules.length === 1}
                  onClick={() => removeRule(index)}
                />
              </Tooltip>
            </Col>
          </Row>
        ))}
      </Space>

      <Button
        type="dashed"
        icon={<PlusOutlined />}
        onClick={() => onChange({ ...value, rules: [...value.rules, defaultRule(factors)] })}
        style={{ marginTop: 10 }}
      >
        Add rule
      </Button>
    </section>
  );
}
