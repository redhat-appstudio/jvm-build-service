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
  ArtifactListDTO, BuildDTO, BuildEditInfo, BuildInfoEditResourceService, BuildRecipeInfo,
  RepositoryInfo,
  ScmEditInfo,
  ScmInfo
} from "../../services/openapi";
import {GithubIcon, HelpIcon} from "@patternfly/react-icons";


type BuildEditModalData = {
  build: BuildDTO,
};

export const BuildEdit: React.FunctionComponent<BuildEditModalData> = (data) => {

  const build: BuildRecipeInfo = {}
  const initial: BuildEditInfo = {
    version: true,
    scmUri: "",
    buildInfo: build
  }
  const [error, setError] = useState(false);
  const [state, setState] = useState('');
  const [info, setInfo] = useState(initial);

  const [prUrl, setPrUrl] = useState('');


  useEffect(() => {
    setState('loading');
    if (data.build.scmRepo == '') {
      return
    }
    BuildInfoEditResourceService.getApiBuildInfoEdit(data.build.scmRepo).then()
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
  }, [data.build]);

  const save = () => {

    BuildInfoEditResourceService.postApiBuildInfoEdit(info)
      .then((ret) => {
        console.log(ret.prUrl)
        setPrUrl(ret.prUrl)
      })
  }

  const stateChange = (e: (scmInfo: BuildEditInfo, val) => void): (k, v) => void => {
    return (_e, v) => {
      const newInfo = {...info}
      e(newInfo, v)
      setInfo(newInfo)
    }
  }

  return (
    <React.Fragment>

        {state == 'loading' && <Spinner aria-label="Loading"/>}
        {state == 'success' && <Form id="edit-artifact-scm-info-form">
          <FormGroup
            label="Apply to Specific Version"
            labelIcon={
              <Popover bodyContent={<div>If this change should be applied to this version and lower.</div>}><HelpIcon/></Popover>
            }
            fieldId="artifact-edit-version">
            <Checkbox id="artifact-edit-version" name="artifact-edit-version"
                      isChecked={info.version}
                      onChange={stateChange((s, v) => {
                        s.version = v
                      })}></Checkbox>
          </FormGroup>
          <FormGroup
            label="Additional Memory"
            labelIcon={
              <Popover bodyContent={<div>Additional Build Memory</div>}><HelpIcon/></Popover>
            }
            fieldId="artifact-edit-uri">
            <TextInput
              type="url"
              id="artifact-edit-memory"
              name="artifact-edit-memory"
              value={info.buildInfo.additionalMemory}
              onChange={stateChange((s, v) => {s.buildInfo.additionalMemory = v})}
            />
          </FormGroup>
          <FormGroup label="Additional Args">
            <Button variant="control" onClick={() => {
              stateChange((s, v) => {
                if (s.buildInfo.additionalArgs) {
                  s.buildInfo.additionalArgs.push("")
                } else {
                  s.buildInfo.additionalArgs = [""]
                }
              })(info.buildInfo, null)
            }}>Add Additional Param</Button>

          </FormGroup>
          {info.buildInfo.additionalArgs?.map((s, index) =>
            <FormGroup
              label="Additional Arg"
              labelIcon={
                <Popover bodyContent={<div>An additional param</div>}><HelpIcon/></Popover>
              }
              fieldId="artifact-edit-additional-arg">
              <TextInput
                id="artifact-edit-additional-arg"
                name="artifact-edit-additional-arg"
                value={s}
                onChange={stateChange((s, v) => {
                  if (s.buildInfo.additionalArgs) {
                    s.buildInfo.additionalArgs[index] = v
                  }
                })}
              />
            </FormGroup>)}
<FormGroup>
  <Button key="create" variant="primary" form="modal-with-form-form" onClick={save} disabled={prUrl.length > 0}>
    Confirm
  </Button>
</FormGroup>
          {prUrl.length == 0 ? <></> :
            <a href={prUrl} target={'_blank'}><GithubIcon></GithubIcon>{prUrl}</a>
          }
        </Form>}
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

    </>
  );
};
