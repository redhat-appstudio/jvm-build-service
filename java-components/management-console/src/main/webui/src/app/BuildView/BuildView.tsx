import * as React from 'react';
import {useEffect, useState} from 'react';

import {
  BuildAttemptDTO,
  BuildAttemptResourceService,
  BuildDTO,
  BuildHistoryResourceService, BuildQueueResourceService
} from "../../services/openapi";
import {RouteComponentProps} from "react-router-dom";
import {
  Card,
  CardBody,
  CardHeader,
  CardTitle,
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
  MenuToggleElement
} from "@patternfly/react-core";
import {CheckCircleIcon, EllipsisVIcon, ErrorCircleOIcon, IceCreamIcon} from "@patternfly/react-icons";


const columnNames = {
  name: 'Build ID',
  repo: 'Repo',
  tag: 'Tag',
  artifacts: 'Artifacts',
  actions: 'Actions',
};

interface RouteParams {
  id: string
}

interface BuildView extends RouteComponentProps<RouteParams> {
}

const BuildView: React.FunctionComponent<BuildView> = (props) => {

  const id = props.match.params.id
  const initial: BuildDTO = {id: 0, name: ""}
  const [build, setBuild] = useState(initial);
  const [error, setError] = useState(false);
  const [state, setState] = useState('');
  const [isMenuOpen, setIsMenuOpen] = React.useState<boolean>(false);

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

  const rebuild = (event: React.SyntheticEvent<HTMLLIElement>) => {
    BuildQueueResourceService.postApiBuildsQueue(build.name)
      .then(() => {
        const copy = Object.assign({}, build);
        copy.inQueue = true
        setBuild(copy)
      })
  };

  const dropdownItems = (
    <>
      {/* Prevent default onClick functionality for example purposes */}
      <DropdownItem key="rebuild" onSelect={rebuild}  onClick={rebuild}>
        Rebuild
      </DropdownItem>
      <DropdownItem key="discovery-logs" to={"/api/builds/history/discovery-logs/" + build.id} target="_blank" >
        Build Discovery Logs
      </DropdownItem>
    </>
  );

  const headerActions = (
    <>
      <Dropdown key="actions-dropdown"
        toggle={(toggleRef: React.Ref<MenuToggleElement>) => (
          <MenuToggle
            ref={toggleRef}
            isExpanded={isMenuOpen}
            onClick={() => setIsMenuOpen(!isMenuOpen)}
            variant="plain"
            aria-label="Actions"
          >
            <EllipsisVIcon aria-hidden="true"/>
          </MenuToggle>
        )}
        isOpen={isMenuOpen}
        onOpenChange={(isOpen: boolean) => setIsMenuOpen(isOpen)}
      >
        <DropdownList>{dropdownItems}</DropdownList>
      </Dropdown>
    </>
  );

  return (
    <Flex>
      <FlexItem>
        <Card>
          <CardHeader actions={{ actions: headerActions }}>
            <CardTitle>Build {build.scmRepo}@{build.tag}{statusIcon(build)} {build.inQueue && <Label color="blue" icon={<IceCreamIcon />}>In Build Queue</Label>}</CardTitle>

          </CardHeader>
          <CardBody>
            <DescriptionList
              columnModifier={{
                default: '2Col'
              }}>
              <DescriptionListGroup>
                <DescriptionListTerm>External Repository</DescriptionListTerm>
                <DescriptionListDescription>{build.scmRepo}</DescriptionListDescription>
              </DescriptionListGroup>
              <DescriptionListGroup>
                <DescriptionListTerm>Context Path</DescriptionListTerm>
                <DescriptionListDescription>{build.contextPath}</DescriptionListDescription>
              </DescriptionListGroup>
              <DescriptionListGroup>
                <DescriptionListTerm>Tag</DescriptionListTerm>
                <DescriptionListDescription>{build.tag}</DescriptionListDescription>
              </DescriptionListGroup>
              <DescriptionListGroup>
                <DescriptionListTerm>Commit Hash</DescriptionListTerm>
                <DescriptionListDescription>{build.commit}</DescriptionListDescription>
              </DescriptionListGroup>
            </DescriptionList>
          </CardBody>
        </Card>
        {build.successfulBuild == undefined ? '' :
          <BuildAttempt attempt={build.successfulBuild} expanded={true}></BuildAttempt>}
        {build.buildAttempts == undefined ? '' : build.buildAttempts.map((build) => (
          <BuildAttempt attempt={build} expanded={false}></BuildAttempt>
        ))}
      </FlexItem>
    </Flex>);
};

type BuildAttemptType = {
  attempt: BuildAttemptDTO
  expanded: boolean
};

const BuildAttempt: React.FunctionComponent<BuildAttemptType> = (data: BuildAttemptType) => {
  const [isExpanded, setIsExpanded] = React.useState(data.expanded);
  const [isMenuOpen, setIsMenuOpen] = React.useState<boolean>(false);

  const onToggle = (_event: React.MouseEvent, isExpanded: boolean) => {
    setIsExpanded(isExpanded);
  };
  if (data.attempt == undefined) {
    return <></>
  }

  const dropdownItems = (
    <>
      {/* Prevent default onClick functionality for example purposes */}
      <DropdownItem key="link" to={"/api/builds/attempts/logs/" + data.attempt?.id} target="_blank" >
        Build Logs
      </DropdownItem>
    </>
  );

  const headerActions = (
    <>
      <Dropdown
        toggle={(toggleRef: React.Ref<MenuToggleElement>) => (
          <MenuToggle
            ref={toggleRef}
            isExpanded={isMenuOpen}
            onClick={() => setIsMenuOpen(!isMenuOpen)}
            variant="plain"
            aria-label="Actions"
          >
            <EllipsisVIcon aria-hidden="true"/>
          </MenuToggle>
        )}
        isOpen={isMenuOpen}
        onOpenChange={(isOpen: boolean) => setIsMenuOpen(isOpen)}
      >
        <DropdownList>{dropdownItems}</DropdownList>
      </Dropdown>
    </>
  );
  return (
    <ExpandableSection toggleContent={'JDK' + data.attempt.jdk + " " + data.attempt.tool} onToggle={onToggle}
                       isExpanded={isExpanded}>
      <Card>
        <CardHeader actions={{ actions: headerActions }}></CardHeader>
        <CardBody>
          <DescriptionList
            columnModifier={{
              default: '2Col'
            }}>
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
            <DescriptionListGroup>
              <DescriptionListTerm>Gradle Version</DescriptionListTerm>
              <DescriptionListDescription>{data.attempt.gradleVersion}</DescriptionListDescription>
            </DescriptionListGroup>
            <DescriptionListGroup>
              <DescriptionListTerm>SBT Version</DescriptionListTerm>
              <DescriptionListDescription>{data.attempt.sbtVersion}</DescriptionListDescription>
            </DescriptionListGroup>
            <DescriptionListGroup>
              <DescriptionListTerm>Ant Version</DescriptionListTerm>
              <DescriptionListDescription>{data.attempt.antVersion}</DescriptionListDescription>
            </DescriptionListGroup>
          </DescriptionList>
        </CardBody>
      </Card>
    </ExpandableSection>
  );
};


export {BuildView}
