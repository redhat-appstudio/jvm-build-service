import * as React from 'react';
import {useEffect, useState} from 'react';

import {
  BuildAttemptDTO,
  BuildDTO,
  BuildHistoryResourceService,
  BuildQueueResourceService
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
  ClipboardCopy,
  ClipboardCopyVariant,
  DescriptionList,
  DescriptionListDescription,
  DescriptionListGroup,
  DescriptionListTerm,
  Dropdown,
  DropdownItem,
  DropdownList,
  Label,
  List,
  ListItem,
  MenuToggle,
  PageSection,
  PageSectionVariants,
  Tab,
  Tabs,
  TabTitleText,
  Text,
  TextContent,
  TextVariants,
  ToggleGroup,
  ToggleGroupItem,
  Toolbar,
  ToolbarContent,
  ToolbarItem
} from "@patternfly/react-core";
import {
  CheckCircleIcon,
  ErrorCircleOIcon,
  IceCreamIcon,
  MinusIcon,
  PlusIcon,
  QuestionIcon,
  WarningTriangleIcon
} from "@patternfly/react-icons";
import {Table, Tbody, Td, Th, Thead, Tr} from "@patternfly/react-table";
import {DependencySet} from "@app/DependencySet/DependencySet";

interface RouteParams {
  name: string
}

interface BuildView extends RouteComponentProps<RouteParams> {
}

const BuildView: React.FunctionComponent<BuildView> = (props) => {

  const name = props.match.params.name
  const initialArray : Array<BuildAttemptDTO> = new Array<BuildAttemptDTO>();
  const initial: BuildDTO = {
    buildAttempts: initialArray,
    buildSbomDependencySetId: 0,
    contaminated: false,
    inQueue: false,
    succeeded: false,
    verified: false,
    id: 0, name: "", scmRepo: "", tag: "", commit: ""
  }
  const rec: Record<string, Array<string>> = {};

  const initialSelected: BuildAttemptDTO = {label: "", id: 0, upstreamDifferences: rec, buildId: ""}
  const [build, setBuild] = useState(initial);
  const [error, setError] = useState(false);
  const [state, setState] = useState('');
  const [dropDownOpen, setDropDownOpen] = useState(false);
  const [selectBuildAttempt, setSelectBuildAttempt] = useState(initialSelected);

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
    BuildHistoryResourceService.getBuild(name).then()
      .then((res) => {
        console.log(res);
        setState('success');
        setBuild(res);
        if (res.buildAttempts?.length > 0) {
          setSelectBuildAttempt(res.buildAttempts[res.buildAttempts?.length - 1]);
        }
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

  const dropDownLabel = (state: BuildAttemptDTO) => {
    if (state.contaminated) {
      return <><CheckCircleIcon color="orange"/> {state.label}</>
    }
    if (state.successful) {
      return <><CheckCircleIcon color="green"/> {state.label}</>
    }
    return <><ErrorCircleOIcon color="red"/> {state.label}</>
  }
  return (
    <>

      <Toolbar id="search-input-filter-toolbar">
        <ToolbarContent>

          <ToolbarItem variant="search-filter">
            <Dropdown
              isOpen={dropDownOpen}
              onOpenChange={(isOpen) => setDropDownOpen(isOpen)}
              onOpenChangeKeys={['Escape']}
              toggle={(toggleRef) => (
                <MenuToggle ref={toggleRef} onClick={() => setDropDownOpen(!dropDownOpen)} isExpanded={dropDownOpen}>
                  {dropDownLabel(selectBuildAttempt)}
                </MenuToggle>
              )}
              id="context-selector"
              onSelect={(e, v) => {
                if (typeof v == 'number') {
                setSelectBuildAttempt(build.buildAttempts[v]);
                setDropDownOpen(false);
              }
              }}
              isScrollable
            >
              <DropdownList>
                {build.buildAttempts.map((at, idx) => <DropdownItem itemId={idx}>{dropDownLabel(at)}</DropdownItem>)}
              </DropdownList>
            </Dropdown>
          </ToolbarItem>
        </ToolbarContent>
      </Toolbar>
      <PageSection variant={PageSectionVariants.light}>
        <TextContent>
          <Text
            component={TextVariants.h1}>Build {build.scmRepo}@{build.tag} {statusIcon(build)} {build.successfulBuild != undefined && !build.successfulBuild.passedVerification ?
            <Label color="orange" icon={<WarningTriangleIcon/>}>Failed
              Verification</Label> : ''} {build.inQueue &&
            <Label color="blue" icon={<IceCreamIcon/>}>In Build Queue</Label>}</Text>
        </TextContent>
      </PageSection>
      <PageSection variant={PageSectionVariants.light}>
        <Card>
          <CardHeader>Source Code Details</CardHeader>
          <CardBody>
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
              {build.successfulBuild != undefined && build.successfulBuild.gitArchiveUrl != undefined && build.successfulBuild.gitArchiveSha != undefined && build.successfulBuild.gitArchiveTag != undefined &&
                <DescriptionListGroup>
                  <DescriptionListTerm>Internal Archive</DescriptionListTerm>
                  <DescriptionListDescription>{gitUri(build.successfulBuild.gitArchiveUrl, build.successfulBuild.gitArchiveTag, build.successfulBuild.gitArchiveSha)}</DescriptionListDescription>
                </DescriptionListGroup>}
              <DescriptionListGroup>
                <DescriptionListTerm>Logs</DescriptionListTerm>
                <DescriptionListDescription>
                  <Link to={"/api/builds/history/discovery-logs/" + build.name} target="_blank">Discovery
                    Logs</Link>
                </DescriptionListDescription>
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
            </ActionList></CardFooter>
        </Card>
      </PageSection>
      {build.buildAttempts.length > 0 &&
      <PageSection isFilled>
        <Tabs activeKey={activeTabKey}
              onSelect={handleTabClick}
              isBox
              aria-label="Tabs in the box light variation example"
              role="region">
          <Tab eventKey={0} title={<TabTitleText>Build Details</TabTitleText>}
               aria-label="Box light variation content - users">
            <Card>
              <CardHeader>Build Details</CardHeader>
               <BuildAttempt attempt={selectBuildAttempt}></BuildAttempt>
            </Card>
          </Tab>
          <Tab eventKey={2}
               disabled={Object.entries(selectBuildAttempt.upstreamDifferences).length == 0}
               title={<TabTitleText>Verification Failures</TabTitleText>}>
            <Card>
              <CardHeader>Verification Failures</CardHeader>
              <CardBody>
                <DescriptionList>
                  {Object.entries(selectBuildAttempt.upstreamDifferences).map(([key, value]) => {
                    return <DescriptionListGroup key={key}>
                      <DescriptionListTerm>{key}</DescriptionListTerm>
                      <DescriptionListDescription>{value.map(d => {
                        if (d.startsWith("+")) {
                          return <><PlusIcon color={'green'}></PlusIcon>{d.substring(1)}<br/></>
                        } else if (d.startsWith("-")) {
                          return <><MinusIcon color={'red'}></MinusIcon>{d.substring(1)}<br/></>
                        } else {
                          return <><QuestionIcon color={'orange'}></QuestionIcon>{d.substring(1)}<br/></>
                        }
                      })
                      }
                      </DescriptionListDescription>
                    </DescriptionListGroup>
                  })}
                </DescriptionList>
              </CardBody>
            </Card>
          </Tab>
          <Tab eventKey={4} disabled={build.successfulBuild == undefined}
               title={<TabTitleText>Artifacts</TabTitleText>}>
            <Card>
              <CardHeader>Quay Image</CardHeader>
              <CardBody>
                <DescriptionList>
                  <List>
                    <ListItem>
                      <a
                        href={selectBuildAttempt.outputImage?.replace(/(quay.io)(.*):(.*)/, "https://quay.io/repository$2/tag/$3")}
                        target="_blank">
                        {build.successfulBuild?.outputImage}
                      </a>
                    </ListItem>
                  </List>
                </DescriptionList>
              </CardBody>
              <CardHeader>Maven Artifacts Repository</CardHeader>
              <CardBody>
                <List>
                  {selectBuildAttempt.artifacts != undefined && selectBuildAttempt.artifacts.map((key) => (
                    <ListItem key={key}>
                      {build.successfulBuild?.mavenRepository != undefined ?
                      <a href={
                      build.successfulBuild?.mavenRepository + (build.successfulBuild?.mavenRepository?.endsWith("/") ? '' : '/') +
                      key.split(":")[0].replace(/\./g, "/") +
                      "/" +
                      key.split(":")[1] +
                      "/" +
                      key.split(":")[2]
                    } target="_blank">{key}</a> : {key}}</ListItem>
                  ))}
                </List>
              </CardBody>
            </Card>
          </Tab>
          <Tab eventKey={5} disabled={selectBuildAttempt.shadingDetails?.length == 0}
               title={<TabTitleText>Shading Details</TabTitleText>}>
            <Card>
              <CardHeader>Shading</CardHeader>
              <CardBody>
                <Table>
                  <Thead>
                    <Th>Shaded Artifact</Th>
                    <Th>Source</Th>
                    <Th>Affected Build Artifacts</Th>
                  </Thead>
                  <Tbody>
                    {selectBuildAttempt.shadingDetails?.map(data => <Tr>
                      <Td>{data.contaminant?.identifier?.group}:{data.contaminant?.identifier?.artifact}:{data.contaminant?.version}</Td>
                      <Td>{data.allowed ?
                        <Label color="green" icon={<CheckCircleIcon/>}>{data.source}</Label> :
                        <Label color="red" icon={<ErrorCircleOIcon/>}>{data.source}</Label>}</Td>
                      <Td>{data.contaminatedArtifacts?.map(key => <>{key.identifier?.artifact} &nbsp;</>)}</Td>
                    </Tr>)}
                  </Tbody>
                </Table>
              </CardBody>
            </Card>
          </Tab>
          <Tab eventKey={6} disabled={build.buildSbomDependencySetId == -1}
               title={<TabTitleText>Build SBom</TabTitleText>}>
            <Card>
              <CardHeader>Build SBom</CardHeader>
              <CardBody>
                <DependencySet dependencySetId={build.buildSbomDependencySetId}></DependencySet>
              </CardBody>
            </Card>
          </Tab>
        </Tabs>
      </PageSection>}
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
          <DescriptionListDescription><Link to={"/api/builds/attempts/logs/" + data.attempt?.buildId}
                                            target="_blank"> Build Logs</Link></DescriptionListDescription>
        </DescriptionListGroup>
        <BuildAttemptDetails attempt={data.attempt}></BuildAttemptDetails>
      </DescriptionList>

    </CardBody>
  </Card>);
};

const BuildAttemptDetails: React.FunctionComponent<BuildAttemptType> = (data: BuildAttemptType) => {
  const [containerRuntime, setContainerRuntime] = useState("");
  return <>
    <DescriptionListGroup>
      <DescriptionListTerm>Start Time</DescriptionListTerm>
      <DescriptionListDescription>{data.attempt.startTime}</DescriptionListDescription>
    </DescriptionListGroup>
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
    </DescriptionListGroup>}
    {data.attempt.diagnosticDockerFile != undefined && <DescriptionListGroup>
      <DescriptionListTerm>Docker File</DescriptionListTerm>
      <DescriptionListDescription>
        <ToggleGroup aria-label="Container Runtime">
          <ToggleGroupItem
            text="None"
            buttonId="none"
            isSelected={containerRuntime === ''}
            onChange={() => setContainerRuntime('')}
          />
          <ToggleGroupItem
            text="Docker"
            buttonId="docker"
            isSelected={containerRuntime === 'docker'}
            onChange={() => setContainerRuntime('docker')}
          />
          <ToggleGroupItem
            text="Podman"
            buttonId="podman"
            isSelected={containerRuntime === 'podman'}
            onChange={() => setContainerRuntime('podman')}
          />
        </ToggleGroup>
        {containerRuntime != "" &&
          <ClipboardCopy hoverTip="Copy" clickTip="Copied" variant={ClipboardCopyVariant.expansion} isReadOnly>
            {`bash -c 'cd $(mktemp -d) && echo ${btoa(data.attempt.diagnosticDockerFile)} | base64  -d >Dockerfile && ${containerRuntime} build --pull . -t diagnostic-${data.attempt.id} && ${containerRuntime} run -it diagnostic-${data.attempt.id}'`}
          </ClipboardCopy>}
      </DescriptionListDescription>
    </DescriptionListGroup>}
  </>
};


export {BuildView}
