import * as React from 'react';
import {useEffect, useState} from 'react';
import {
  DataList,
  DataListCell,
  DataListContent,
  DataListItem,
  DataListItemCells,
  DataListItemRow,
  DataListToggle,
  Pagination, Toolbar, ToolbarContent, ToolbarItem,
} from '@patternfly/react-core';
import {
  ImageDTO,
  ImageResourceService,

} from "../../services/openapi";
import {EmptyTable} from '@app/EmptyTable/EmptyTable';
import {
  ContainerNodeIcon
} from "@patternfly/react-icons";
import {useParams} from "react-router-dom";
import {DependencySet} from "../../components";


const ImageList = () => {

  const { repo } = useParams() as { repo: string}
  const [images, setImages] = useState(Array<ImageDTO>);
  const [error, setError] = useState(false);
  const [state, setState] = useState('');

  const [count, setCount] = React.useState(0);
  const [page, setPage] = React.useState(1);
  const [perPage, setPerPage] = React.useState(20);

  useEffect(() => {
    setState('loading');
    ImageResourceService.getApiImage(repo, page, perPage).then()
      .then((res) => {
        console.log(res);
        setState('success');
        setImages(res.items);
        setCount(res.count)
        if (res.perPage != perPage) {
          setPerPage(res.perPage)
        }
        if (res.pageNo != page) {
          setPage(res.pageNo)
        }
      })
      .catch((err) => {
        console.error('Error:', err);
        setState('error');
        setError(err);
      });
  }, [page, perPage]);

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

  const onSetPage = (_event: React.MouseEvent | React.KeyboardEvent | MouseEvent, newPage: number) => {
    setPage(newPage);
  };

  const onPerPageSelect = (
    _event: React.MouseEvent | React.KeyboardEvent | MouseEvent,
    newPerPage: number,
    newPage: number
  ) => {
    setPerPage(newPerPage);
    setPage(newPage);
  };
  return (
    <React.Fragment>
      <Toolbar>
      <ToolbarContent>

        <ToolbarItem variant="pagination"><Pagination
        titles={{ paginationAriaLabel: 'Search filter pagination' }}
        itemCount={count}
        widgetId="search-input-mock-pagination"
        perPage={perPage}
        page={page}
        onPerPageSelect={onPerPageSelect}
        onSetPage={onSetPage}
        isCompact
        /></ToolbarItem></ToolbarContent></Toolbar>
      <DataList aria-label="Information">
      {images.map((image : ImageDTO, index) => (
          <ImageRow key={index} image={image}></ImageRow>
        ))}
        {images.length === 0 && <EmptyTable></EmptyTable>}
      </DataList>
    </React.Fragment>
  );
};

type ImageType = {
  image: ImageDTO,
};

const ImageRow: React.FunctionComponent<ImageType> = (image): JSX.Element => {

  const [imageExpanded, setImageExpanded] = React.useState(false);

  const toggleImages = () => {
    setImageExpanded(!imageExpanded);
  };

  return <DataListItem aria-labelledby="ex-item1" isExpanded={imageExpanded}>
    <DataListItemRow>
      {image.image.analysisComplete && <DataListToggle
        onClick={() => toggleImages()}
        isExpanded={imageExpanded}
        id="toggle"
        aria-controls="ex-expand"
      />}
      <DataListItemCells
        dataListCells={[
          <DataListCell isIcon key="icon">
            <ContainerNodeIcon/>
          </DataListCell>,
          <DataListCell key="primary content">
            <div id="ex-item1">{image.image.fullName}</div>
          </DataListCell>
        ]}
      />
    </DataListItemRow>
    <DataListContent
      aria-label="Image Details"
      id="ex-expand1"
      isHidden={!imageExpanded}
    ><DependencySet dependencySetId={image.image.dependencySet}></DependencySet>
    </DataListContent>
  </DataListItem>

}
export {ImageList};
