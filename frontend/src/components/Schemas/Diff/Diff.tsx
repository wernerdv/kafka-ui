import React from 'react';
import { SchemaSubject } from 'generated-sources';
import {
  clusterSchemaComparePath,
  clusterSchemasPath,
  ClusterSubjectParam,
} from 'lib/paths';
import PageLoader from 'components/common/PageLoader/PageLoader';
import DiffViewer from 'components/common/DiffViewer/DiffViewer';
import { useNavigate, useLocation } from 'react-router-dom';
import { useForm, Controller } from 'react-hook-form';
import Select from 'components/common/Select/Select';
import useAppParams from 'lib/hooks/useAppParams';
import { useGetSchemasVersions } from 'lib/hooks/api/schemas';
import ResourcePageHeading from 'components/common/ResourcePageHeading/ResourcePageHeading';

import * as S from './Diff.styled';
import { BackButton } from './Diff.styled';

const Diff: React.FC = () => {
  const { clusterName, subject } = useAppParams<ClusterSubjectParam>();
  const { data: versions, isFetching: areVersionsFetching } =
    useGetSchemasVersions({ clusterName, subject });

  const navigate = useNavigate();
  const location = useLocation();

  const searchParams = React.useMemo(
    () => new URLSearchParams(location.search),
    [location]
  );

  const [leftVersion, setLeftVersion] = React.useState(
    searchParams.get('leftVersion') || ''
  );
  const [rightVersion, setRightVersion] = React.useState(
    searchParams.get('rightVersion') || ''
  );

  const getSchemaContent = (allVersions: SchemaSubject[], version: string) => {
    const selectedSchema =
      allVersions.find((s) => s.version === version)?.schema ||
      (allVersions.length ? allVersions[0].schema : '');
    return selectedSchema.trim().startsWith('{')
      ? JSON.stringify(JSON.parse(selectedSchema), null, '\t')
      : selectedSchema;
  };
  const getSchemaType = (allVersions: SchemaSubject[]) => {
    return allVersions[0].schemaType;
  };

  const methods = useForm({ mode: 'onChange' });
  const {
    formState: { isSubmitting },
    control,
  } = methods;

  return (
    <>
      <ResourcePageHeading
        text={`${subject} compare versions`}
        backText="Schema Registry"
        backTo={clusterSchemasPath(clusterName)}
      />
      <BackButton
        buttonType="secondary"
        buttonSize="S"
        onClick={() => navigate(-1)}
      >
        Back
      </BackButton>
      <S.Section>
        {!areVersionsFetching && versions ? (
          <S.DiffBox>
            <S.DiffTilesWrapper>
              <S.DiffTile>
                <S.DiffVersionsSelect>
                  <Controller
                    defaultValue={leftVersion}
                    control={control}
                    rules={{ required: true }}
                    name="schemaType"
                    render={({ field: { name } }) => (
                      <Select
                        id="left-select"
                        name={name}
                        value={
                          leftVersion === '' ? versions[0].version : leftVersion
                        }
                        onChange={(event) => {
                          navigate(
                            clusterSchemaComparePath(clusterName, subject)
                          );
                          searchParams.set('leftVersion', event.toString());
                          searchParams.set(
                            'rightVersion',
                            rightVersion === ''
                              ? versions[0].version
                              : rightVersion
                          );
                          navigate({
                            search: `?${searchParams.toString()}`,
                          });
                          setLeftVersion(event.toString());
                        }}
                        minWidth="100%"
                        disabled={isSubmitting}
                        options={versions.map((type) => ({
                          value: type.version,
                          label: `Version ${type.version}`,
                        }))}
                      />
                    )}
                  />
                </S.DiffVersionsSelect>
              </S.DiffTile>
              <S.DiffTile>
                <S.DiffVersionsSelect>
                  <Controller
                    defaultValue={rightVersion}
                    control={control}
                    rules={{ required: true }}
                    name="schemaType"
                    render={({ field: { name } }) => (
                      <Select
                        id="right-select"
                        name={name}
                        value={
                          rightVersion === ''
                            ? versions[0].version
                            : rightVersion
                        }
                        onChange={(event) => {
                          navigate(
                            clusterSchemaComparePath(clusterName, subject)
                          );
                          searchParams.set(
                            'leftVersion',
                            leftVersion === ''
                              ? versions[0].version
                              : leftVersion
                          );
                          searchParams.set('rightVersion', event.toString());
                          navigate({
                            search: `?${searchParams.toString()}`,
                          });
                          setRightVersion(event.toString());
                        }}
                        minWidth="100%"
                        disabled={isSubmitting}
                        options={versions.map((type) => ({
                          value: type.version,
                          label: `Version ${type.version}`,
                        }))}
                      />
                    )}
                  />
                </S.DiffVersionsSelect>
              </S.DiffTile>
            </S.DiffTilesWrapper>
            <S.DiffWrapper>
              <DiffViewer
                value={[
                  getSchemaContent(versions, leftVersion),
                  getSchemaContent(versions, rightVersion),
                ]}
                setOptions={{
                  autoScrollEditorIntoView: true,
                }}
                isFixedHeight={false}
                schemaType={getSchemaType(versions)}
              />
            </S.DiffWrapper>
          </S.DiffBox>
        ) : (
          <PageLoader />
        )}
      </S.Section>
    </>
  );
};

export default Diff;
