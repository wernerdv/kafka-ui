import React from 'react';
import { FormProvider, useForm } from 'react-hook-form';
import { ClusterNameRoute, clusterTopicsPath } from 'lib/paths';
import TopicForm from 'components/Topics/shared/Form/TopicForm';
import { useLocation, useNavigate } from 'react-router-dom';
import { yupResolver } from '@hookform/resolvers/yup';
import { topicFormValidationSchema } from 'lib/yupExtended';
import useAppParams from 'lib/hooks/useAppParams';
import { useCreateTopic } from 'lib/hooks/api/topics';
import { TopicFormData } from 'lib/interfaces/topic';
import ResourcePageHeading from 'components/common/ResourcePageHeading/ResourcePageHeading';

enum Filters {
  NAME = 'name',
  PARTITION_COUNT = 'partitionCount',
  REPLICATION_FACTOR = 'replicationFactor',
  INSYNC_REPLICAS = 'inSyncReplicas',
  CLEANUP_POLICY = 'cleanUpPolicy',
}

const New: React.FC = () => {
  const { clusterName } = useAppParams<ClusterNameRoute>();
  const methods = useForm<TopicFormData>({
    mode: 'onChange',
    resolver: yupResolver(topicFormValidationSchema),
  });

  const createTopic = useCreateTopic(clusterName);

  const navigate = useNavigate();

  const { search } = useLocation();
  const params = new URLSearchParams(search);

  const name = params.get(Filters.NAME) || '';
  const partitionCount = params.get(Filters.PARTITION_COUNT) || 1;
  const replicationFactor = params.get(Filters.REPLICATION_FACTOR) || 1;
  const inSyncReplicas = params.get(Filters.INSYNC_REPLICAS) || 1;
  const cleanUpPolicy = params.get(Filters.CLEANUP_POLICY) || 'Delete';

  const onSubmit = async (data: TopicFormData) => {
    try {
      await createTopic.createResource(data);
      navigate(`../${data.name}`);
    } catch (e) {
      // do nothing
    }
  };

  return (
    <>
      <ResourcePageHeading
        text={search ? 'Copy' : 'Create'}
        backText="Topics"
        backTo={clusterTopicsPath(clusterName)}
      />
      <FormProvider {...methods}>
        <TopicForm
          topicName={name}
          cleanUpPolicy={cleanUpPolicy}
          partitionCount={Number(partitionCount)}
          replicationFactor={Number(replicationFactor)}
          inSyncReplicas={Number(inSyncReplicas)}
          isSubmitting={createTopic.isLoading}
          onSubmit={methods.handleSubmit(onSubmit)}
        />
      </FormProvider>
    </>
  );
};

export default New;
