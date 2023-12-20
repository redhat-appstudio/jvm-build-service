import * as React from 'react';
import {
    ActionGroup,
    Button,
    Form,
    FormGroup,
    FormHelperText,
    HelperText,
    HelperTextItem,
    Modal,
    SimpleList,
    SimpleListItem,
    Split,
    SplitItem,
    TextArea,
} from '@patternfly/react-core';
import {BuildQueueResourceService} from "../../services/openapi";

export const AddArtifact: React.FunctionComponent = () => {
    const formId = "submitGAV"
    const [txtValue, setInput] = React.useState('');
    const [resultValue, setResultInput] = React.useState(Array<string>);
    const [isModalOpen, setIsModalOpen] = React.useState(false);

    const handleModalToggle = (_event: KeyboardEvent | React.MouseEvent) => {
        setIsModalOpen((prevIsModalOpen) => !prevIsModalOpen);
    };
    const handleChange = (event) => {
        event.preventDefault();
        setInput(event.target.value);
    };

    function handleSubmit(event) {
        event.preventDefault();
        if (txtValue.length != 0) {
            const promises = new Array<Promise<any>>();
            const result = new Array<string>();
            let gavs: string[]
            gavs = txtValue.trim().split(/[\n,]/)
            for (let gav of gavs) {
                console.log("Creating build for " + gav)
                promises.push(
                    BuildQueueResourceService.postApiBuildsQueueAdd(gav).then(() => {
                        console.log("Submitted! Code: " + gav)
                        result.push(gav + "@submitted")
                    })
                        .catch((error) => {
                            // The error comes through as a json object in the body with 'details' and 'stack' keys.
                            // console.log("Caught error " + JSON.stringify(error.body))
                            result.push(gav + "@" + error.body.details)
                            console.log("Caught error " + error.body.details)
                        }))
            }
            Promise.all(promises).then(() => {
                setResultInput(result)
                handleModalToggle(event)
                setInput("")
            })
        }
    }

    return <React.Fragment>
        <Form id={formId} onSubmit={handleSubmit} isWidthLimited={true}>
            <FormHelperText>
                <HelperText>
                    <HelperTextItem><br/>Enter a list of newline or comma separated "group:artifact:version"</HelperTextItem>
                </HelperText>
            </FormHelperText>
            <FormGroup label="GAVs" fieldId="horizontal-form-exp">
                <TextArea
                    value={txtValue}
                    onChange={handleChange}
                    id="horizontal-form-exp"
                    name="horizontal-form-exp"
                />
            </FormGroup>
            <ActionGroup>
                <Button variant="primary" ouiaId="Primary" form={formId} type="submit">Submit</Button>
            </ActionGroup>
        </Form>
        <Modal
            title="Results"
            isOpen={isModalOpen}
            onClose={handleModalToggle}
            actions={[
                <Button key="Ok" variant="primary" onClick={handleModalToggle}>
                    Ok
                </Button>,
            ]}>
            <Split>
                <SplitItem>
                    <SimpleList>
                        {resultValue.length > 0 && resultValue.map((item) => (
                            <SimpleListItem>{item.split("@")[0]}</SimpleListItem>
                        ))}
                    </SimpleList>
                </SplitItem>
                <SplitItem>
                    <SimpleList>
                        {resultValue.length > 0 && resultValue.map((item) => (
                            <SimpleListItem>{item.split("@")[1]}</SimpleListItem>
                        ))}
                    </SimpleList>
                </SplitItem>
            </Split>
        </Modal>
    </React.Fragment>
};
