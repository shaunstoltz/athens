import { BADGE, Storybook } from '../../storybook';

import { Embed } from './Embed';
import { Block } from '../../components/Block';
import { blocks } from '../../components/Block/mockData';

export default {
  title: 'Components/Embed',
  component: Embed,
  argTypes: {},
  decorators: [(Story) => <Storybook.Wrapper><Story /></Storybook.Wrapper>],
  parameters: {
    badges: [BADGE.DEV]
  }
};

export const Youtube = () => <Embed type="youtube" url="https://www.youtube.com/embed/dQw4w9WgXcQ" />;
export const Image = () => <Embed type="image" url="https://images.unsplash.com/photo-1518791841217-8f162f1e1131?ixlib=rb-1.2.1&ixid=eyJhcHBfaWQiOjEyMDd9&auto=format&fit=crop&w=800&q=60" />;
export const InABlock = () => <>
  <Block {...blocks[0]} />
  <Block
    {...blocks[1]}
    rawContent="![cat](https://images.unsplash.com/photo-1518791841217-8f162f1e1131?ixlib=rb-1.2.1&ixid=eyJhcHBfaWQiOjEyMDd9&auto=format&fit=crop&w=800&q=60)"
    renderedContent={<Embed type="image" url="https://images.unsplash.com/photo-1518791841217-8f162f1e1131?ixlib=rb-1.2.1&ixid=eyJhcHBfaWQiOjEyMDd9&auto=format&fit=crop&w=800&q=60" />}
  />
  <Block {...blocks[2]}
  />
</>;

