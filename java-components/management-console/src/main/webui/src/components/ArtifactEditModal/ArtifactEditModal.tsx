import React from 'react';
import {Modal, ModalVariant, Button, Form, FormGroup, Popover, TextInput, Checkbox} from '@patternfly/react-core';
import {ArtifactEditResourceService, ArtifactListDTO, ModifyScmRepoCommand} from "../../services/openapi";
import {GithubIcon} from "@patternfly/react-icons";
import {Link} from "react-router-dom";


type ArtifactEditModalData = {
  artifact: ArtifactListDTO,
  open: boolean
  setOpen:  (value: (((prevState: boolean) => boolean) | boolean)) => void
};

export const ArtifactEditModal: React.FunctionComponent<ArtifactEditModalData> = (data) => {
  const [groupValue, setGroupValue] = React.useState(false);
  const [versionValue, setVersionValue] = React.useState(false);
  const [legacyValue, setLegacyValue] = React.useState(false);
  const [uriValue, setUriValue] = React.useState('');
  const [pathValue, setPathValue] = React.useState('');
  const [prUrl, setPrUrl] = React.useState('');

  const handleLegacyInputChange = (_event, value: boolean) => {
    setLegacyValue(value);
  };
  const handleGroupInputChange = (_event, value: boolean) => {
    setGroupValue(value);
  };
  const handleVersionInputChange = (_event, value: boolean) => {
    setVersionValue(value);
  };
  const handlePathInputChange = (_event, value: string) => {
    setPathValue(value);
  };
  const handleURIInputChange = (_event, value: string) => {
    setUriValue(value);
  };

  const save = () => {
    let cmd: ModifyScmRepoCommand= {
      gav: data.artifact.gav,
      group : groupValue,
      uri: uriValue,
      path: pathValue,
      legacy: legacyValue,
      version: versionValue
    }

    ArtifactEditResourceService.postApiArtifactsEdit(cmd)
      .then((ret) =>{
        console.log(ret.prUrl)
        setPrUrl(ret.prUrl)
      })
  }

  return (
    <React.Fragment>
      <Modal
        variant={ModalVariant.small}
        title="Create account"
        description="Enter your personal information below to create an account."
        isOpen={data.open}
        onClose={() => {data.setOpen(false)}}
        actions={[
          <Button key="create" variant="primary" form="modal-with-form-form" onClick={save} disabled={prUrl.length > 0}>
            Confirm
          </Button>,
          <Button key="cancel" variant="link" onClick={() => {data.setOpen(false)}}>
            Close
          </Button>
        ]}
      >
        <Form id="edit-artifact-scm-info-form">
          <FormGroup
            label="URI"
            labelIcon={
              <Popover bodyContent={<div>The SCM URI</div>} ></Popover>
            }
            fieldId="artifact-edit-uri"  >
            <TextInput
              type="url"
              id="artifact-edit-uri"
              name="artifact-edit-uri"
              value={uriValue}
              onChange={handleURIInputChange}
            />
          </FormGroup>
          <FormGroup
            label="Path"
            labelIcon={
              <Popover bodyContent={<div>The path inside the repo to build from</div>} ></Popover>
            }
            fieldId="artifact-edit-path" >
            <TextInput
              type="url"
              id="artifact-edit-path"
              name="artifact-edit-path"
              value={pathValue}
              onChange={handlePathInputChange}
            />
          </FormGroup>
          <FormGroup
            label="Apply to Group"
            labelIcon={
              <Popover bodyContent={<div>If this change should be applied at a group level</div>} ></Popover>
            }
            fieldId="artifact-edit-group" >
            <Checkbox id="artifact-edit-group" name="artifact-edit-group" isChecked={groupValue} onChange={handleGroupInputChange} ></Checkbox>
          </FormGroup>
          <FormGroup
            label="Apply to Specific Version"
            labelIcon={
              <Popover bodyContent={<div>If this change should be applied to this version and lower.</div>} ></Popover>
            }
            fieldId="artifact-edit-version" >
            <Checkbox id="artifact-edit-version" name="artifact-edit-version" isChecked={versionValue} onChange={handleVersionInputChange} ></Checkbox>
          </FormGroup>
          <FormGroup
            label="Legacy Repository"
            labelIcon={
              <Popover bodyContent={<div>If this SCM information is a legacy repository.</div>} ></Popover>
            }
            fieldId="artifact-edit-legacy" >
            <Checkbox id="artifact-edit-legacy" name="artifact-edit-legacy" isChecked={legacyValue} onChange={handleLegacyInputChange}></Checkbox>
          </FormGroup>
          {prUrl.length == 0 ? <></> :
              <a href={prUrl} target={'_blank'}><GithubIcon></GithubIcon>{prUrl}</a>
          }
        </Form>
      </Modal>
    </React.Fragment>
  );
};
