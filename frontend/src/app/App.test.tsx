import { screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { renderWithProviders } from '../shared/test/render';
import { routes } from './router';

describe('AppShell', () => {
  it('zobrazí rám s prázdnym menu a stránku plánu ako „pripravuje sa“', async () => {
    const router = createMemoryRouter(routes, { initialEntries: ['/'] });
    renderWithProviders(<RouterProvider router={router} />);
    expect(await screen.findByText('Plán ubytovania – pripravuje sa')).toBeInTheDocument();
    expect(screen.getByText('TheraSuite')).toBeInTheDocument();
    expect(screen.getByText('by N-teq')).toBeInTheDocument();
    expect(screen.getByTestId('main-menu')).toBeEmptyDOMElement();
  });
});
