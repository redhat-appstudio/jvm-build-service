import * as React from 'react';
import {
  ActionGroup,
  Button,
  Form,
  FormGroup,
  FormHelperText,
  HelperText,
  HelperTextItem,
  TextArea,
} from '@patternfly/react-core';
import {BuildQueueResourceService} from "../../services/openapi";

export const AddArtifact: React.FunctionComponent = () => {
  const [txtValue, setInput] = React.useState('');

  const handleChange = (event) => {
    event.preventDefault();
    setInput(event.target.value);
  };

  function handleSubmit(event) {
    event.preventDefault();
    if (txtValue.length != 0) {
      let gavs: string[]
      gavs = txtValue.trim().split(",")
      for (let gav of gavs) {
        console.log("Creating build for " + gav)
        BuildQueueResourceService.postApiBuildsQueueAdd(gav).then(() => {
        })
        setInput("")
      }
    }
  }

  return <React.Fragment>
    <Form onSubmit={handleSubmit} isWidthLimited={true}>
      <FormHelperText>
        <HelperText>
          <HelperTextItem><br/>Enter comma separated GAVs</HelperTextItem>
        </HelperText>
      </FormHelperText>
      <FormGroup>
        <TextArea
            value={txtValue}
            onChange={handleChange}
            id="horizontal-form-exp"
            name="horizontal-form-exp"
        />
      </FormGroup>
      <ActionGroup>
        <Button variant="primary" ouiaId="Primary">Submit</Button>
      </ActionGroup>
    </Form>
  </React.Fragment>
};
