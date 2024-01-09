import * as React from 'react';
import {useEffect, useState} from 'react';

import {
    BuildAttemptDTO,
    BuildDTO,
    BuildHistoryResourceService, BuildListDTO, BuildQueueResourceService
} from "../../services/openapi";
import {Link, RouteComponentProps} from "react-router-dom";
import {
  ActionList,
  ActionListItem, Button,
  Card,
  CardBody, CardFooter,
  CardHeader,
  CardTitle, CodeBlock, CodeBlockCode,
  DescriptionList,
  DescriptionListDescription,
  DescriptionListGroup,
  DescriptionListTerm,
  Dropdown,
  DropdownItem, DropdownList,
  ExpandableSection,
  Flex,
  FlexItem,
  Label,
  MenuToggle,
  MenuToggleElement, PageSection, PageSectionVariants, Tab, Tabs, TabTitleText, Text, TextContent, TextVariants
} from "@patternfly/react-core";
import {
    CheckCircleIcon,
    EllipsisVIcon,
    ErrorCircleOIcon,
    IceCreamIcon, WarningTriangleIcon
} from "@patternfly/react-icons";
import {H1} from "@storybook/components";

interface RouteParams {
    id: string
}

interface BuildView extends RouteComponentProps<RouteParams> {
}

const BuildView: React.FunctionComponent<BuildView> = (props) => {

    const id = props.match.params.id
    const initial: BuildDTO = {id: 0, name: "", scmRepo: "", tag: "", commit: ""}
    const [build, setBuild] = useState(initial);
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
        BuildHistoryResourceService.getBuild(Number(id)).then()
            .then((res) => {
                console.log(res);
                setState('success');
                setBuild(res);
            })
            .catch((err) => {
                console.error('Error:', err);
                setState('error');
                setError(err);
            });
    }, [id]);

    if (state === 'error')
        return (
            <h1>
                {error.toString()}
            </h1>
        );
    if (state === 'loading')
        return (<h1>Loading...</h1>)

    const statusIcon = function (build: BuildDTO) {
        if (build.contaminated) {
            return <Label color="orange" icon={<CheckCircleIcon/>}>
                Build Contaminated
            </Label>
        } else if (build.succeeded) {
            return <Label color="green" icon={<CheckCircleIcon/>}>
                Build Successful
            </Label>
        }
        return <Label color="red" icon={<ErrorCircleOIcon/>}>
            Build Failed
        </Label>
    }

    const rebuild = () => {
        BuildQueueResourceService.postApiBuildsQueue(build.name)
            .then(() => {
                const copy = Object.assign({}, build);
                copy.inQueue = true
                setBuild(copy)
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
            return <a target={'_blank'} href={url + "/tree/" + commit + build.contextPath} rel="noreferrer">{tag}</a>
        }
        return <a target={'_blank'} href={url + "/-/tree/" + commit + build.contextPath} rel="noreferrer">{tag}</a>
    }

    return (
      <>
        <PageSection variant={PageSectionVariants.light}>
          <TextContent>
            <Text component={TextVariants.h1}>Build {build.scmRepo}@{build.tag} {statusIcon(build)} {build.successfulBuild != undefined && !build.successfulBuild.passedVerification ?
              <Label color="orange" icon={<WarningTriangleIcon/>}>Failed
                Verification</Label> : ''} {build.inQueue &&
              <Label color="blue" icon={<IceCreamIcon/>}>In Build Queue</Label>}</Text>
          </TextContent>
        </PageSection>
        <PageSection isFilled>
          <Tabs  activeKey={activeTabKey}
                 onSelect={handleTabClick}
                 isBox
                 aria-label="Tabs in the box light variation example"
                 role="region" >
            <Tab eventKey={0} title={<TabTitleText>Build Details</TabTitleText>} aria-label="Box light variation content - users">
              <Card>
                <CardHeader>Build Details</CardHeader>
                <CardBody >

                  <DescriptionList
                    columnModifier={{
                      default: '2Col'
                    }}>
                    <DescriptionListGroup>
                      <DescriptionListTerm>Internal Id</DescriptionListTerm>
                      <DescriptionListDescription>{build.name}</DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                      <DescriptionListTerm>External Repository</DescriptionListTerm>
                      <DescriptionListDescription>{gitUri(build.scmRepo, build.tag, build.commit)}</DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                      <DescriptionListTerm>Commit Hash</DescriptionListTerm>
                      <DescriptionListDescription>{build.commit}</DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                      <DescriptionListTerm>Logs</DescriptionListTerm>
                      <DescriptionListDescription>
                        <Link to={"/api/builds/attempts/logs/" + build.successfulBuild?.id} target="_blank"> Build Logs</Link>
                        <br/>
                        <Link to={"/api/builds/history/discovery-logs/" + build.id} target="_blank">Discovery Logs</Link>
                      </DescriptionListDescription>
                    </DescriptionListGroup>
                    {build.successfulBuild != undefined && build.successfulBuild.gitArchiveUrl != undefined && build.successfulBuild.gitArchiveSha != undefined && build.successfulBuild.gitArchiveTag != undefined &&
                      <DescriptionListGroup>
                        <DescriptionListTerm>Internal Archive</DescriptionListTerm>
                        <DescriptionListDescription>{gitUri(build.successfulBuild.gitArchiveUrl, build.successfulBuild.gitArchiveTag, build.successfulBuild.gitArchiveSha)}</DescriptionListDescription>
                      </DescriptionListGroup>}
                    {build.successfulBuild != undefined && <BuildAttemptDetails attempt={build.successfulBuild}></BuildAttemptDetails>}
                  </DescriptionList>
                </CardBody>

                <CardFooter>
                <ActionList>
                  <ActionListItem>
                    <Button variant="secondary" id="single-group-next-button" onClick={rebuild}>
                      Rebuild
                    </Button>
                  </ActionListItem>
                </ActionList></CardFooter>
              </Card>
            </Tab>
            <Tab eventKey={1} disabled={build.buildAttempts == undefined || build.buildAttempts.length == 0} title={<TabTitleText>Failed Build Attempts</TabTitleText>}>
              {build.buildAttempts == undefined ? '' : build.buildAttempts.map((build) => (
                <Card key={build.id} >
                <BuildAttempt attempt={build}></BuildAttempt></Card>
              ))}
            </Tab>
            <Tab eventKey={2} disabled={build.successfulBuild == undefined || Object.entries(build.successfulBuild.upstreamDifferences).length == 0} title={<TabTitleText>Verification Failures</TabTitleText>}>
              <Card>
                <CardHeader>Verification Failures</CardHeader>
                <CardBody>
                  <DescriptionList>
                    {build.successfulBuild != undefined && Object.entries(build.successfulBuild.upstreamDifferences).map(([key, value]) => {
                      return <DescriptionListGroup key={key}>
                        <DescriptionListTerm>{key}</DescriptionListTerm>
                        <DescriptionListDescription>
                          <CodeBlock >
                            <CodeBlockCode id="code-content">{value}</CodeBlockCode>
                          </CodeBlock>
                        </DescriptionListDescription>
                      </DescriptionListGroup>
                    })}
                  </DescriptionList>
                </CardBody>
              </Card>
            </Tab>

            <Tab eventKey={3} disabled={build.successfulBuild == undefined} title={<TabTitleText>Deployed Artifacts</TabTitleText>}>
              <Card>
                <CardHeader>Artifacts</CardHeader>
                <CardBody>
                  <DescriptionList>
                    {build.artifacts != undefined && build.artifacts.map(key => <>{key}</>)}
                  </DescriptionList>
                </CardBody>
              </Card>
            </Tab>
            <Tab eventKey={4} disabled={build.shadingDetails?.length == 0} title={<TabTitleText>Shading Details</TabTitleText>}>
              <Card>
                <CardHeader>Shading</CardHeader>
                <CardBody>
                  {build.shadingDetails?.map(key => <>{key.contaminant?.identifier?.group}:{key.contaminant?.identifier?.artifact}:{key.contaminant?.version}</>)}
                </CardBody>
              </Card>
            </Tab>
          </Tabs>
        </PageSection>
      </>);
};

type BuildAttemptType = {
    attempt: BuildAttemptDTO
};

const BuildAttempt: React.FunctionComponent<BuildAttemptType> = (data: BuildAttemptType) => {
    const statusIcon = function (build: BuildAttemptDTO) {

        if (build.successful) {
            return <Label color="green" icon={<CheckCircleIcon/>}>
                Build Successful
            </Label>
        }
        return <Label color="red" icon={<ErrorCircleOIcon/>}>
            Build Failed
        </Label>
    }

    return (<Card>
                <CardHeader>{'JDK' + data.attempt.jdk + " " + data.attempt.tool + " " + (data.attempt.tool === "maven" ? data.attempt.mavenVersion : data.attempt.tool === "gradle" ? data.attempt.gradleVersion : "")}{statusIcon(data.attempt)}</CardHeader>
                <CardBody>
                    <DescriptionList
                        columnModifier={{
                            default: '2Col'
                        }}>
                      <DescriptionListGroup>
                        <DescriptionListTerm>Logs</DescriptionListTerm>
                        <DescriptionListDescription><Link to={"/api/builds/attempts/logs/" + data.attempt?.id} target="_blank"> Build Logs</Link></DescriptionListDescription>
                      </DescriptionListGroup>
                      <BuildAttemptDetails attempt={data.attempt}></BuildAttemptDetails>
                    </DescriptionList>

                </CardBody>
            </Card>);
};

const BuildAttemptDetails: React.FunctionComponent<BuildAttemptType> = (data: BuildAttemptType) => {


  return <>
        <DescriptionListGroup>
          <DescriptionListTerm>JDK</DescriptionListTerm>
          <DescriptionListDescription>{data.attempt.jdk}</DescriptionListDescription>
        </DescriptionListGroup>
        <DescriptionListGroup>
          <DescriptionListTerm>Tool</DescriptionListTerm>
          <DescriptionListDescription>{data.attempt.tool}</DescriptionListDescription>
        </DescriptionListGroup>
        <DescriptionListGroup>
          <DescriptionListTerm>Maven Version</DescriptionListTerm>
          <DescriptionListDescription>{data.attempt.mavenVersion}</DescriptionListDescription>
        </DescriptionListGroup>
        {data.attempt.gradleVersion != undefined && <DescriptionListGroup>
          <DescriptionListTerm>Gradle Version</DescriptionListTerm>
          <DescriptionListDescription>{data.attempt.gradleVersion}</DescriptionListDescription>
        </DescriptionListGroup>}
        {data.attempt.sbtVersion != undefined && <DescriptionListGroup>
          <DescriptionListTerm>SBT Version</DescriptionListTerm>
          <DescriptionListDescription>{data.attempt.sbtVersion}</DescriptionListDescription>
        </DescriptionListGroup>}
        {data.attempt.antVersion != undefined && <DescriptionListGroup>
          <DescriptionListTerm>Ant Version</DescriptionListTerm>
          <DescriptionListDescription>{data.attempt.antVersion}</DescriptionListDescription>
        </DescriptionListGroup>}</>
};


export {BuildView}
