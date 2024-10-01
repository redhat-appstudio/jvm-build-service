import * as React from 'react';
import {Route, Routes, useLocation} from 'react-router-dom';
import {Dashboard} from '@app/Dashboard/Dashboard';
import {NotFound} from '@app/NotFound/NotFound';
import {useDocumentTitle} from '@app/utils/useDocumentTitle';
import {BuildQueueList} from "@app/BuildQueueList/BuildQueueList";
import {BuildView} from "@app/BuildView/BuildView";
import {BuildList} from "@app/BuildList/BuildList";
import {RunningBuildList} from "@app/RunningBuildList/RunningBuildList";
import {ControlPanel} from "@app/ControlPanel/ControlPanel";
import {ArtifactList} from "@app/ArtifactList/ArtifactList";
import {ArtifactView} from "@app/ArtifactView/ArtifactView";
import {AddArtifact} from "@app/AddArtifact/AddArtifact";
import {GithubBuildList} from "@app/GithubBuildList/GithubBuildList";
import {GithubBuildView} from "@app/GithubBuildView/GithubBuildView";

let routeFocusTimer: number;
export interface IAppRoute {
  label?: string; // Excluding the label will exclude the route from the nav sidebar in AppLayout
  /* eslint-disable @typescript-eslint/no-explicit-any */
  component: | React.ComponentType<any>;
  /* eslint-enable @typescript-eslint/no-explicit-any */
  exact?: boolean;
  path: string;
  title: string;
  routes?: undefined;
}

export interface IAppRouteGroup {
  label: string;
  routes: IAppRoute[];
}

export type AppRouteConfig = IAppRoute | IAppRouteGroup;

const routes: AppRouteConfig[] = [
  {
    component: Dashboard,
    exact: true,
    label: 'Home',
    path: '/',
    title: 'JVM Build Service',
  },
  {
    label: 'Builds',
    routes: [
      {
        component: BuildList,
        exact: true,
        label: 'All Builds',
        path: '/builds/all',
        title: 'JVM Build Service | Build List',
      },
      {
        component: BuildView,
        exact: true,
        path: '/builds/build/:name',
        title: 'JVM Build Service | Build',
      },
      {
        component: RunningBuildList,
        exact: true,
        label: 'Running Builds',
        path: '/builds/running',
        title: 'JVM Build Service | Running Build List',
      },
      {
        component: BuildQueueList,
        exact: true,
        label: 'Build Queue',
        path: '/builds/queue',
        title: 'JVM Build Service | Build Queue',
      },
    ],
  },
  {
    label: 'Artifacts',
    routes: [
      {
        component: ArtifactList,
        exact: true,
        label: 'All Artifacts',
        path: '/artifacts/all',
        title: 'JVM Build Service | Artifact List',
      },
      {
        component: ArtifactView,
        exact: true,
        path: '/artifacts/artifact/:name',
        title: 'JVM Build Service | Artifact',
      },
      {
        component: AddArtifact,
        exact: true,
        label: 'Add Artifact',
        path: '/artifacts/create',
        title: 'JVM Build Service | Add Artifact',
      },
    ],
  },
  {
    label: 'CI',
    routes: [
      {
        component: GithubBuildList,
        exact: true,
        label: 'Github Actions',
        path: '/builds/github/all',
        title: 'JVM Build Service | Github Actions Builds',
      },
      {
        component: GithubBuildView,
        path: '/builds/github/build/:build',
        title: 'JVM Build Service | Github Actions Build',
      },
    ],
  },
  {
    label: 'Admin',
    routes: [
      {
        component: ControlPanel,
        exact: true,
        label: 'Control Panel',
        path: '/admin/control-panel',
        title: 'JVM Build Service | Control Panel',
      },
    ],
  },
];

// a custom hook for sending focus to the primary content container
// after a view has loaded so that subsequent press of tab key
// sends focus directly to relevant content
// may not be necessary if https://github.com/ReactTraining/react-router/issues/5210 is resolved
const useA11yRouteChange = () => {
  const { pathname } = useLocation();
  React.useEffect(() => {
    routeFocusTimer = window.setTimeout(() => {
      const mainContainer = document.getElementById('primary-app-container');
      if (mainContainer) {
        mainContainer.focus();
      }
    }, 50);
    return () => {
      window.clearTimeout(routeFocusTimer);
    };
  }, [pathname]);
};

const RouteWithTitle = ({ component: Component, title, ...rest }: IAppRoute) => {
  useA11yRouteChange();
  useDocumentTitle(title);
  return <Component {...rest}/>
}

const PageNotFound = ({ title }: { title: string }) => {
  useDocumentTitle(title);
  return <NotFound/>;
};

const flattenedRoutes: IAppRoute[] = routes.reduce(
  (flattened, route) => [...flattened, ...(route.routes ? route.routes : [route])],
  [] as IAppRoute[]
);
const AppRoutes = (): React.ReactElement => (
  <Routes>
    {flattenedRoutes.map(({ path, component, title }, idx) => (
      <Route path={path} key={idx} element={
        <RouteWithTitle path={path} component={component} key={idx} title={title} />
      }/>
    ))}
    <Route path="*" element={
      <PageNotFound title="404 Page Not Found" />
    }/>
  </Routes>
);

export { AppRoutes, routes };
