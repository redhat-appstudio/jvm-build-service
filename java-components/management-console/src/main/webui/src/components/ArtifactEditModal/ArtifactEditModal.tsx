import React, {Fragment, useEffect, useState} from 'react';
import {
  Button,
  Checkbox,
  Form,
  FormGroup,
  Modal,
  ModalVariant,
  Popover,
  Spinner,
  TextInput
} from '@patternfly/react-core';
import {
  ArtifactEditResourceService,
  ArtifactListDTO,
  RepositoryInfo,
  ScmEditInfo,
  ScmInfo
} from "../../services/openapi";
import {GithubIcon} from "@patternfly/react-icons";


type ArtifactEditModalData = {
  artifact: ArtifactListDTO,
  open: boolean
  setOpen: (value: (((prevState: boolean) => boolean) | boolean)) => void
};

export const ArtifactEditModal: React.FunctionComponent<ArtifactEditModalData> = (data) => {

  const scm: ScmInfo = {}
  const initial: ScmEditInfo = {
    version: true,
    group: true,
    gav: "",
    scmInfo: scm
  }
  const [error, setError] = useState(false);
  const [state, setState] = useState('');
  const [info, setInfo] = useState(initial);

  const [prUrl, setPrUrl] = useState('');


  useEffect(() => {
    setState('loading');
    if (data.artifact.gav == '') {
      return
    }
    ArtifactEditResourceService.getApiArtifactsEdit(data.artifact.gav).then()
      .then((res) => {
        console.log(res);
        setState('success');
        setInfo(res)
      })
      .catch((err) => {
        console.error('Error:', err);
        setState('error');
        setError(err);
      });
  }, [data.artifact]);

  const save = () => {

    ArtifactEditResourceService.postApiArtifactsEdit(info)
      .then((ret) => {
        console.log(ret.prUrl)
        setPrUrl(ret.prUrl)
      })
  }

  const stateChange = (e: (scmInfo: ScmEditInfo, val) => void): (k, v) => void => {
    return (_e, v) => {
      const newInfo = {...info}
      e(newInfo, v)
      setInfo(newInfo)
    }
  }

  return (
    <React.Fragment>
      <Modal
        variant={ModalVariant.small}
        title="Edit Artifact"
        isOpen={data.open}
        onClose={() => {
          data.setOpen(false)
        }}
        actions={[
          <Button key="create" variant="primary" form="modal-with-form-form" onClick={save} disabled={prUrl.length > 0}>
            Confirm
          </Button>,
          <Button key="cancel" variant="link" onClick={() => {
            data.setOpen(false)
          }}>
            Close
          </Button>
        ]}
      >
        {state == 'loading' && <Spinner aria-label="Loading"/>}
        {state == 'success' && <Form id="edit-artifact-scm-info-form">
          <FormGroup
            label="Apply to Group"
            labelIcon={
              <Popover bodyContent={<div>If this change should be applied at a group level</div>}></Popover>
            }
            fieldId="artifact-edit-group">
            <Checkbox id="artifact-edit-group" name="artifact-edit-group"
                      isChecked={info.group}
                      onChange={stateChange((s, v) => {
                        s.group = v
                      })}></Checkbox>
          </FormGroup>
          <FormGroup
            label="Apply to Specific Version"
            labelIcon={
              <Popover bodyContent={<div>If this change should be applied to this version and lower.</div>}></Popover>
            }
            fieldId="artifact-edit-version">
            <Checkbox id="artifact-edit-version" name="artifact-edit-version"
                      isChecked={info.version}
                      onChange={stateChange((s, v) => {
                        s.version = v
                      })}></Checkbox>
          </FormGroup>
          <RepositorySection repo={info.scmInfo} edit={(s) => {
            const newInfo = {...info}
            newInfo.scmInfo = s
            setInfo(newInfo)
          }}></RepositorySection>
          <FormGroup label="Legacy Repos">
            <Button variant="control" onClick={() => {
              const newInfo = {...info}
              if (newInfo.scmInfo.legacyRepos) {
                newInfo.scmInfo.legacyRepos.push({})
              } else {
                newInfo.scmInfo.legacyRepos = [{}]
              }
              setInfo(newInfo)
            }}>Add Legacy Repo</Button>

          </FormGroup>
          {info.scmInfo.legacyRepos?.map((s, index) =>
            <RepositorySection repo={s} edit={(s) => {
              const newInfo = {...info}
              if (newInfo.scmInfo.legacyRepos) {
                newInfo.scmInfo.legacyRepos[index] = s
              }
              setInfo(newInfo)
            }}></RepositorySection>)}

          {prUrl.length == 0 ? <></> :
            <a href={prUrl} target={'_blank'}><GithubIcon></GithubIcon>{prUrl}</a>
          }
        </Form>}
      </Modal>
    </React.Fragment>
  );
};


type RepositorySectionData = {
  repo: RepositoryInfo | ScmInfo
  edit: (o: RepositoryInfo | ScmInfo) => void
};

export const RepositorySection: React.FunctionComponent<RepositorySectionData> = (data) => {

  const stateChange = (e: (scmInfo: RepositoryInfo | ScmInfo, val) => void): (k, v) => void => {
    return (_e, v) => {
      const newInfo = {...data.repo}
      e(newInfo, v)
      data.edit(newInfo)
    }
  }

  return (
    <>
      <FormGroup
        label="URI"
        labelIcon={
          <Popover bodyContent={<div>The SCM URI</div>}></Popover>
        }
        fieldId="artifact-edit-uri">
        <TextInput
          type="url"
          id="artifact-edit-uri"
          name="artifact-edit-uri"
          value={data.repo.uri}
          onChange={stateChange((s, v) => {
            s.uri = v
          })}
        />
      </FormGroup>
      <FormGroup
        label="Path"
        labelIcon={
          <Popover bodyContent={<div>The path inside the repo to build from</div>}></Popover>
        }
        fieldId="artifact-edit-path">
        <TextInput
          type="url"
          id="artifact-edit-path"
          name="artifact-edit-path"
          value={data.repo.path}
          onChange={stateChange((s, v) => {
            s.path = v
          })}
        />
      </FormGroup>
      <FormGroup label="Tag Mappings">
        <Button variant="control" onClick={() => {
          stateChange((s, v) => {
            if (s.tagMapping) {
              s.tagMapping.push({})
            } else {
              s.tagMapping = [{}]
            }
          })(data.repo, null)
        }}>Add Tag Mapping</Button>

      </FormGroup>
      {data.repo.tagMapping?.map((s, index) =>
        <>
          <FormGroup key={index}
                     label="Tag Pattern"
                     labelIcon={
                       <Popover bodyContent={<div>A tag Mapping</div>}></Popover>
                     }
                     fieldId="artifact-edit-path">
            <TextInput
              id="artifact-edit-pattern"
              name="artifact-edit-pattern"
              value={s.pattern}
              onChange={stateChange((s, v) => {
                if (s.tagMapping) {
                  s.tagMapping[index].pattern = v
                }
              })}
            />
          </FormGroup>
          <FormGroup key={index}
                     label="Tag Mapping"
                     labelIcon={
                       <Popover bodyContent={<div>A tag Mapping</div>}></Popover>
                     }
                     fieldId="artifact-edit-path">
            <TextInput
              id="artifact-edit-tag"
              name="artifact-edit-tag"
              value={s.tag}
              onChange={stateChange((s, v) => {
                if (s.tagMapping) {
                  s.tagMapping[index].tag = v
                }
              })}
            />
          </FormGroup></>)}
    </>
  );
};
