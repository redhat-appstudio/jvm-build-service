import * as React from 'react';
import {useEffect, useState} from 'react';

import {ArtifactLabelName, ArtifactLabelResourceService} from "../../services/openapi";
import {Dropdown, DropdownItem, DropdownList, MenuToggle} from "@patternfly/react-core";

type LabelSelectorType = {
  labelSelected: (string) => void
  labelValueSelected?: (string) => void
};
const LabelSelector: React.FunctionComponent<LabelSelectorType> = (props) => {
  const [labels, setLabels] = useState(Array<ArtifactLabelName>);
  const [label, setLabel] = useState('');
  const [error, setError] = useState(false);
  const [state, setState] = useState('');
  const [dropDownOpen, setDropDownOpen] = useState(false);

  useEffect(() => {
    setState('loading');
    ArtifactLabelResourceService.getApiArtifactLabels().then()
      .then((res) => {
        setState('success');
        setLabels(res);
      })
      .catch((err) => {
        console.error('Error:', err);
        setState('error');
        setError(err);
      });
  }, []);

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

  const onSelect = (ev: React.MouseEvent<Element, MouseEvent> | undefined, itemId: string | number | undefined) => {
    if (typeof itemId === 'number' || typeof itemId === 'undefined') {
      return;
    }
    setLabel(itemId);
    setDropDownOpen(!dropDownOpen);
    props.labelSelected(itemId)
  };

  return <Dropdown
    isOpen={dropDownOpen}
    onOpenChange={(isOpen) => setDropDownOpen(isOpen)}
    onOpenChangeKeys={['Escape']}
    toggle={(toggleRef) => (
      <MenuToggle ref={toggleRef} onClick={() => setDropDownOpen(!dropDownOpen)} isExpanded={dropDownOpen}>
        {label == '' ? 'Label' : label}
      </MenuToggle>
    )}
    id="context-selector"
    onSelect={onSelect}
    isScrollable
  >
    <DropdownList>
      <DropdownItem itemId={''} key={'allitems'} onSelect={() => setLabel('')}>All</DropdownItem>
      {labels.map((item, index) => {
        return (
          <DropdownItem
            itemId={item.name}
            key={index}
            onSelect={() => setLabel(item.name == undefined ? '' : item.name)}
          >
            {item.name == '' ? 'All' : item.name}
          </DropdownItem>
        );
      })}
    </DropdownList>
  </Dropdown>

}

export {LabelSelector}
