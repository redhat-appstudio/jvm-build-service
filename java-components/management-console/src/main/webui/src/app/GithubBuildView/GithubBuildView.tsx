import * as React from 'react';
import {useEffect, useState} from 'react';
import {
  Card, CardBody,
  CardHeader,
  DataList,
  DataListCell,
  DataListContent,
  DataListItem,
  DataListItemCells,
  DataListItemRow,
  DataListToggle,
  Label,
  PageSection,
  PageSectionVariants,
  Pagination, TabTitleText,
  Text,
  TextContent,
  TextVariants,
  Title,
  Toolbar,
  ToolbarContent,
  ToolbarItem,
} from '@patternfly/react-core';
import {
  IdentifiedDependencyDTO,
  GithubBuildDTO, GithubBuildsResourceService
} from "../../services/openapi";
import {EmptyTable} from '@app/EmptyTable/EmptyTable';
import {
  AttentionBellIcon,ContainerNodeIcon,
  IceCreamIcon,
  InProgressIcon, ListIcon, OkIcon, OutlinedAngryIcon, RedhatIcon, StickyNoteIcon, WarningTriangleIcon
} from "@patternfly/react-icons";
import {Link, RouteComponentProps} from "react-router-dom";
import {DependencySet} from "@app/DependencySet/DependencySet";

interface RouteParams {
  build: string
}

interface GithubBuildView extends RouteComponentProps<RouteParams> {
}


const GithubBuildView: React.FunctionComponent<GithubBuildView> = (props) => {
  const initial : GithubBuildDTO = {
    url: "",
    buildsComponent: false, dependencySetId: 0, id: 0, name: ""
  }
  const repo = parseInt(props.match.params.build)
  const [build, setBuild] = useState(initial);
  const [error, setError] = useState(false);
  const [state, setState] = useState('');


  useEffect(() => {
    setState('loading');
    GithubBuildsResourceService.getApiBuildsGithubId(repo).then()
      .then((res) => {
        console.log(res);
        setBuild(res);
        setState('success');
      })
      .catch((err) => {
        console.error('Error:', err);
        setState('error');
        setError(err);
      });
  }, [repo])

  if (state === 'error')
    return (
      <h1>
        {error.toString()}
      </h1>
    );
  if (state === 'loading')
    return (
      <h1>Loading...</h1>
    )

  return <>
    <PageSection variant={PageSectionVariants.light}>
      <TextContent>
        <a href={build.url} target="_blank"><Text component={TextVariants.h1}>Github Build {build.name}</Text></a>
      </TextContent>
    </PageSection>
    <PageSection isFilled>
          <Card>
            <CardHeader>Build Details</CardHeader>
            <CardBody >
              <DependencySet dependencySetId={build.dependencySetId}></DependencySet>
            </CardBody>
          </Card>
    </PageSection>
    </>
};
export {GithubBuildView};
