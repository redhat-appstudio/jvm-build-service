import * as React from 'react';
import {useEffect, useState} from 'react';

import {
  ArtifactDTO,
  ArtifactHistoryResourceService, BuildQueueResourceService,
} from "../../services/openapi";
import {Link, RouteComponentProps} from "react-router-dom";
import {
  ActionList,
  ActionListItem,
  Button,
  Card,
  CardBody,
  CardFooter,
  CardHeader,
  DescriptionList,
  DescriptionListDescription,
  DescriptionListGroup,
  DescriptionListTerm,
  Label,
  PageSection,
  PageSectionVariants,
  Tab,
  Tabs,
  TabTitleText,
  Text,
  TextContent,
  TextVariants
} from "@patternfly/react-core";
import {CheckCircleIcon, ErrorCircleOIcon, WarningTriangleIcon} from "@patternfly/react-icons";

interface RouteParams {
    name: string
}

interface ArtifactView extends RouteComponentProps<RouteParams> {
}

const ArtifactView: React.FunctionComponent<ArtifactView> = (props) => {

    const name = props.match.params.name
    const initial: ArtifactDTO = {id: 0, name: "", gav: "", scmRepo: "", tag: "", commit: ""}
    const [artifact, setArtifact] = useState(initial);
    const [error, setError] = useState(false);
    const [state, setState] = useState('');

  const [activeTabKey, setActiveTabKey] = React.useState<string | number>(0);
  // Toggle currently active tab
  const handleTabClick = (
    event: React.MouseEvent<any> | React.KeyboardEvent | MouseEvent,
    tabIndex: string | number
  ) => {
    setActiveTabKey(tabIndex);
  };


    useEffect(() => {
        setState('loading');
        ArtifactHistoryResourceService.getArtifact(name).then()
            .then((res) => {
                console.log(res);
                setState('success');
                setArtifact(res);
            })
            .catch((err) => {
                console.error('Error:', err);
                setState('error');
                setError(err);
            });
    }, [name]);

    if (state === 'error')
        return (
            <h1>
                {error.toString()}
            </h1>
        );
    if (state === 'loading')
        return (<h1>Loading...</h1>)

    const statusIcon = function (artifact: ArtifactDTO) {
      if (artifact.succeeded) {
        return <Label color="green" icon={<CheckCircleIcon/>}>
          Artifact Successful
        </Label>
      } else if (artifact.missing) {
        return <Label color="orange" icon={<WarningTriangleIcon/>}>
          Artifact Missing
        </Label>
      }
      return <Label color="red" icon={<ErrorCircleOIcon/>}>
        Artifact Failed
      </Label>
    }

    const rebuild = () => {
        BuildQueueResourceService.postApiBuildsQueue(artifact.dependencyBuildName)
            .then(() => {
                const copy = Object.assign({}, artifact);
                setArtifact(copy)
            })
    };

    const gitUri = (url: string, tag: string, commit: string) => {
        if (url == undefined) {
            return <></>
        }
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length - 4)
        }
        if (url.startsWith("https://github.com")) {
            return <a target={'_blank'} href={url + "/tree/" + commit + artifact.contextPath} rel="noreferrer">{tag}</a>
        }
        return <a target={'_blank'} href={url + "/-/tree/" + commit + artifact.contextPath} rel="noreferrer">{tag}</a>
    }

    return (
      <>
        <PageSection variant={PageSectionVariants.light}>
          <TextContent>
            <Text component={TextVariants.h1}>Artifact {artifact.scmRepo}@{artifact.tag} {statusIcon(artifact)} </Text>
          </TextContent>
        </PageSection>
        <PageSection isFilled>
          <Tabs  activeKey={activeTabKey}
                 onSelect={handleTabClick}
                 isBox
                 aria-label="Tabs in the box light variation example"
                 role="region" >
            <Tab eventKey={0} title={<TabTitleText>Artifact Details</TabTitleText>} aria-label="Box light variation content - users">
              <Card>
                <CardHeader>Artifact Details</CardHeader>
                <CardBody >

                  <DescriptionList
                    columnModifier={{
                      default: '2Col'
                    }}>
                    <DescriptionListGroup>
                      <DescriptionListTerm>Internal Id</DescriptionListTerm>
                      <DescriptionListDescription>{artifact.name}</DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                      <DescriptionListTerm>GAV</DescriptionListTerm>
                      <DescriptionListDescription>{artifact.gav}</DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                      <DescriptionListTerm>External Repository</DescriptionListTerm>
                      <DescriptionListDescription>{gitUri(artifact.scmRepo, artifact.tag, artifact.commit)}</DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                      <DescriptionListTerm>Commit Hash</DescriptionListTerm>
                      <DescriptionListDescription>{artifact.commit}</DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                      <DescriptionListTerm>Tag</DescriptionListTerm>
                      <DescriptionListDescription>{artifact.tag}</DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                      <DescriptionListTerm>Message</DescriptionListTerm>
                      <DescriptionListDescription>{artifact.message}</DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                      <DescriptionListTerm>Dependency Build</DescriptionListTerm>
                      <DescriptionListDescription>
                        <Link to={`/builds/build/${artifact.dependencyBuildName}`}>{artifact.dependencyBuildName}</Link>
                      </DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                      <DescriptionListTerm>Context Path</DescriptionListTerm>
                      <DescriptionListDescription>{artifact.contextPath}</DescriptionListDescription>
                    </DescriptionListGroup>
                  </DescriptionList>
                </CardBody>

                <CardFooter>
                <ActionList>
                  <ActionListItem>
                    <Button variant="secondary" id="single-group-next-button" onClick={rebuild}>
                      Rebuild
                    </Button>
                  </ActionListItem>
                </ActionList>
                </CardFooter>
              </Card>
            </Tab>
          </Tabs>
        </PageSection>
      </>);
};


export {ArtifactView}
