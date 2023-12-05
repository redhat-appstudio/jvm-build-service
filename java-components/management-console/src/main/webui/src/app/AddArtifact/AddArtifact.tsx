import * as React from 'react';
import {HelperText, HelperTextItem, TextArea,} from '@patternfly/react-core';
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
    <HelperText>
      <HelperTextItem><br/>Enter comma separated GAVs</HelperTextItem>
    </HelperText>
    <form method="post" onSubmit={handleSubmit}>
      <TextArea value={txtValue} onChange={handleChange} aria-label="AddArtifact" />
      <br/>
      <input type="submit" value={"Submit"}/>
    </form>
  </React.Fragment>
};
