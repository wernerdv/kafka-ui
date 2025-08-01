import React, { useEffect, useState } from 'react';
import {
  useAnalyzeTopic,
  useCancelTopicAnalysis,
  useTopicAnalysis,
} from 'lib/hooks/api/topics';
import useAppParams from 'lib/hooks/useAppParams';
import { RouteParamsClusterTopic } from 'lib/paths';
import * as Informers from 'components/common/Metrics';
import ProgressBar from 'components/common/ProgressBar/ProgressBar';
import {
  List,
  Label,
} from 'components/common/PropertiesList/PropertiesList.styled';
import BytesFormatted from 'components/common/BytesFormatted/BytesFormatted';
import { calculateTimer, formatTimestamp } from 'lib/dateTimeHelpers';
import { Action, ResourceType } from 'generated-sources';
import { ActionButton } from 'components/common/ActionComponent';
import { useTimezone } from 'lib/hooks/useTimezones';

import * as S from './Statistics.styles';
import Total from './Indicators/Total';
import SizeStats from './Indicators/SizeStats';
import PartitionTable from './PartitionTable';
import { LabelValue } from './Statistics.styles';

const Metrics: React.FC = () => {
  const params = useAppParams<RouteParamsClusterTopic>();
  const { currentTimezone } = useTimezone();

  const [isAnalyzing, setIsAnalyzing] = useState(true);
  const analyzeTopic = useAnalyzeTopic(params);
  const cancelTopicAnalysis = useCancelTopicAnalysis(params);

  const { data } = useTopicAnalysis(params, isAnalyzing);

  useEffect(() => {
    if (data && !data.progress) {
      setIsAnalyzing(false);
    }
  }, [data]);

  if (!data) {
    return null;
  }

  if (data.progress) {
    return (
      <S.ProgressContainer>
        <S.ProgressPct>
          {Math.floor(data.progress.completenessPercent || 0)}%
        </S.ProgressPct>
        <S.ProgressBarWrapper>
          <ProgressBar completed={data.progress.completenessPercent || 0} />
        </S.ProgressBarWrapper>
        <ActionButton
          onClick={async () => {
            await cancelTopicAnalysis.mutateAsync();
            setIsAnalyzing(true);
          }}
          buttonType="secondary"
          buttonSize="M"
          permission={{
            resource: ResourceType.TOPIC,
            action: Action.ANALYSIS_RUN,
            value: params.topicName,
          }}
        >
          Stop Analysis
        </ActionButton>
        <List>
          <Label>Started at</Label>
          <LabelValue>
            {formatTimestamp({
              timestamp: data.progress.startedAt,
              format: {
                hour: 'numeric',
                minute: 'numeric',
                second: 'numeric',
              },
              timezone: currentTimezone.value,
            })}
          </LabelValue>
          <Label>Passed since start</Label>
          <LabelValue>
            {calculateTimer(data.progress.startedAt as number)}
          </LabelValue>
          <Label>Scanned messages</Label>
          <LabelValue>{data.progress.msgsScanned}</LabelValue>
          <Label>Scanned size</Label>
          <LabelValue>
            <BytesFormatted value={data.progress.bytesScanned} />
          </LabelValue>
        </List>
      </S.ProgressContainer>
    );
  }

  if (!data.result) {
    return null;
  }

  const totalStats = data.result.totalStats || {};
  const partitionStats = data.result.partitionStats || [];

  return (
    <>
      <S.ActionsBar>
        <S.CreatedAt>
          {formatTimestamp({
            timezone: currentTimezone.value,
            timestamp: data?.result?.finishedAt,
          })}
        </S.CreatedAt>
        <ActionButton
          onClick={async () => {
            await analyzeTopic.mutateAsync();
            setIsAnalyzing(true);
          }}
          buttonType="primary"
          buttonSize="S"
          permission={{
            resource: ResourceType.TOPIC,
            action: Action.ANALYSIS_RUN,
            value: params.topicName,
          }}
        >
          Restart Analysis
        </ActionButton>
      </S.ActionsBar>
      <Informers.Wrapper>
        <Total {...totalStats} />
        {totalStats.keySize && (
          <SizeStats stats={totalStats.keySize} title="Key size" />
        )}
        {totalStats.valueSize && (
          <SizeStats stats={totalStats.valueSize} title="Value size" />
        )}
      </Informers.Wrapper>
      <PartitionTable data={partitionStats} />
    </>
  );
};

export default Metrics;
