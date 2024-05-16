import React, {Fragment, useEffect, useState} from 'react';
import {
  Button,
  Checkbox,
  Form,
  FormGroup,
  Popover,
  Spinner, TextArea,
  TextInput
} from '@patternfly/react-core';
import {
  BuildDTO, BuildEditInfo, BuildInfoEditResourceService, BuildRecipeInfo, RepositoryInfo, ScmInfo
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
          <FormGroup label="Allowed Differences to Upstream">
            <Button variant="control" onClick={() => {
              stateChange((s, v) => {
                if (s.buildInfo.allowedDifferences) {
                  s.buildInfo.allowedDifferences.push("")
                } else {
                  s.buildInfo.allowedDifferences = [""]
                }
              })(info.buildInfo, null)
            }}>Add Allowed Difference</Button>

          </FormGroup>
          {info.buildInfo.allowedDifferences?.map((s, index) =>
            <FormGroup
              label="Difference"
              labelIcon={
                <Popover bodyContent={<div>A regex that matches a validation failure to ignore</div>}><HelpIcon/></Popover>
              }
              fieldId="artifact-edit-upstream-diff">
              <TextInput
                id="artifact-edit-upstream-diff"
                name="artifact-edit-upstream-diff"
                value={s}
                onChange={stateChange((s, v) => {
                  if (s.buildInfo.allowedDifferences) {
                    s.buildInfo.allowedDifferences[index] = v
                  }
                })}
              />
            </FormGroup>)}
          <FormGroup
            label="Additional Memory"
            labelIcon={
              <Popover bodyContent={<div>Additional Build Memory</div>}><HelpIcon/></Popover>
            }
            fieldId="artifact-edit-memory">
            <TextInput
              id="artifact-edit-memory"
              name="artifact-edit-memory"
              value={info.buildInfo.additionalMemory}
              onChange={stateChange((s, v) => {s.buildInfo.additionalMemory = v})}
            />
          </FormGroup>
          <FormGroup
            label="Enforce Version"
            labelIcon={
              <Popover bodyContent={<div>If the version should be enforced when building.</div>}><HelpIcon/></Popover>
            }
            fieldId="artifact-enforce-version">
            <Checkbox id="artifact-enforce-version" name="artifact-enforce-version"
                      isChecked={info.buildInfo.enforceVersion}
                      onChange={stateChange((s, v) => {
                        s.buildInfo.enforceVersion = v
                      })}></Checkbox>
          </FormGroup>
          <FormGroup
            label="Java Version"
            labelIcon={
              <Popover bodyContent={<div>Override Java version detection</div>}><HelpIcon/></Popover>
            }
            fieldId="artifact-edit-java-version">
            <TextInput
              id="artifact-edit-java-version"
              name="artifact-edit-java-version"
              value={info.buildInfo.javaVersion}
              onChange={stateChange((s, v) => {s.buildInfo.javaVersion = v})}
            />
          </FormGroup>
          <FormGroup
            label="Pre-build Script"
            labelIcon={
              <Popover bodyContent={<div>A script to run before the build</div>}><HelpIcon/></Popover>
            }
            fieldId="artifact-edit-prebuild">
            <TextArea
              resizeOrientation="vertical"
              id="artifact-edit-prebuild"
              name="artifact-edit-prebuild"
              value={info.buildInfo.preBuildScript}
              onChange={stateChange((s, v) => {s.buildInfo.preBuildScript = v})}
            />
          </FormGroup>
          <FormGroup
            label="Post-build Script"
            labelIcon={
              <Popover bodyContent={<div>A script to run after the build</div>}><HelpIcon/></Popover>
            }
            fieldId="artifact-edit-postbuild">
            <TextArea
              resizeOrientation="vertical"
              id="artifact-edit-postbuild"
              name="artifact-edit-postbuild"
              value={info.buildInfo.postBuildScript}
              onChange={stateChange((s, v) => {s.buildInfo.postBuildScript = v})}
            />
          </FormGroup>
          <FormGroup label="Repositories">
            <Button variant="control" onClick={() => {
              stateChange((s, v) => {
                if (s.buildInfo.repositories) {
                  s.buildInfo.repositories.push("")
                } else {
                  s.buildInfo.repositories = [""]
                }
              })(info.buildInfo, null)
            }}>Add Additional Repositories</Button>

          </FormGroup>
          {info.buildInfo.repositories?.map((s, index) =>
            <FormGroup
              label="Additional Repository"
              labelIcon={
                <Popover bodyContent={<div>An additional repository from <a target="_blank" href="https://github.com/redhat-appstudio/jvm-build-data/tree/main/repository-info">here</a></div>}><HelpIcon/></Popover>
              }
              fieldId="artifact-edit-repository-arg">
              <TextInput
                id="artifact-edit-repository-arg"
                name="artifact-edit-repository-arg"
                value={s}
                onChange={stateChange((s, v) => {
                  if (s.buildInfo.repositories) {
                    s.buildInfo.repositories[index] = v
                  }
                })}
              />
            </FormGroup>)}
          <FormGroup
            label="Tool Version"
            labelIcon={
              <Popover bodyContent={<div>Override Tool version detection</div>}><HelpIcon/></Popover>
            }
            fieldId="artifact-edit-tool-version">
            <TextInput
              id="artifact-edit-tool-version"
              name="artifact-edit-tool-version"
              value={info.buildInfo.toolVersion}
              onChange={stateChange((s, v) => {s.buildInfo.toolVersion = v})}
            />
          </FormGroup>
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
