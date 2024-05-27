import * as React from 'react';
import {useEffect, useState} from 'react';
import {
  Card, CardBody,
  CardHeader,
  PageSection,
  PageSectionVariants,
  Tab, Tabs, TabTitleText,
  Text,
  TextContent,
  TextVariants,
} from '@patternfly/react-core';
import {
  GithubBuildDTO, GithubBuildsResourceService
} from "../../services/openapi";
import {useParams} from "react-router-dom";
import {DependencySet} from "../../components";

const GithubBuildView = () => {
  const initial : GithubBuildDTO = {
    url: "",
    buildsComponent: false, dependencySetId: 0, id: 0, name: "",buildDependencySetId:-1
  }
  const { build } = useParams() as { build : string}
  const repo = parseInt(build)
  const [ghBuild, setGhBuild] = useState(initial);
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
    GithubBuildsResourceService.getApiBuildsGithubId(repo).then()
      .then((res) => {
        console.log(res);
        setGhBuild(res);
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
        <a href={ghBuild.url} target="_blank"><Text component={TextVariants.h1}>Github Build {ghBuild.name}</Text></a>
      </TextContent>
    </PageSection>
    <PageSection isFilled>

      <Tabs  activeKey={activeTabKey}
             onSelect={handleTabClick}
             isBox
             aria-label="Tabs in the box light variation example"
             role="region" >
        <Tab eventKey={0} title={<TabTitleText>Runtime Dependencies</TabTitleText>} aria-label="Runtime dependencies">
          <Card>
            <CardHeader>Build Dependencies</CardHeader>
            <CardBody >
              <DependencySet dependencySetId={ghBuild.dependencySetId}></DependencySet>
            </CardBody>
          </Card>
        </Tab>
        <Tab eventKey={1} title={<TabTitleText>All Build Dependencies</TabTitleText>} aria-label="All build dependencies">
          <Card>
            <CardHeader>All Build Dependencies</CardHeader>
            <CardBody >
              <DependencySet dependencySetId={ghBuild.buildDependencySetId}></DependencySet>
            </CardBody>
          </Card>
        </Tab>
      </Tabs>
    </PageSection>
    </>
};
export {GithubBuildView};
