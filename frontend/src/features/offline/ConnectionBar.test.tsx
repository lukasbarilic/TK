import { Button } from '@mantine/core';
import { act, screen } from '@testing-library/react';
import { connection } from '../../shared/offline';
import { WriteAction } from '../../shared/offline/WriteAction';
import { renderWithProviders } from '../../shared/test/render';
import { ConnectionBar } from './ConnectionBar';

afterEach(() => connection.reset('online'));

describe('ConnectionBar a WriteAction', () => {
  it('lišta ukazuje stav spojenia', () => {
    renderWithProviders(<ConnectionBar />);
    expect(screen.getByTestId('connection-bar')).toHaveAttribute('data-state', 'online');
    act(() => connection.setState('checking'));
    expect(screen.getByText('Overujem spojenie…')).toBeInTheDocument();
    act(() => connection.setState('offline'));
    expect(screen.getByTestId('connection-bar')).toHaveAttribute('data-state', 'offline');
    expect(screen.getByText(/Offline – len na čítanie/)).toBeInTheDocument();
    act(() => connection.setState('session-expired'));
    expect(screen.getByText(/Relácia vypršala/)).toBeInTheDocument();
  });

  it('zámok: lišta sa neukazuje', () => {
    renderWithProviders(<ConnectionBar />);
    act(() => connection.setState('locked'));
    expect(screen.queryByTestId('connection-bar')).toBeNull();
  });

  it('WriteAction je aktívne len online', () => {
    renderWithProviders(
      <WriteAction>
        <Button>Uložiť</Button>
      </WriteAction>,
    );
    expect(screen.getByRole('button', { name: 'Uložiť' })).toBeEnabled();
    act(() => connection.setState('offline'));
    expect(screen.getByRole('button', { name: 'Uložiť' })).toBeDisabled();
    expect(screen.getByTestId('write-disabled')).toBeInTheDocument();
    act(() => connection.setState('online'));
    expect(screen.getByRole('button', { name: 'Uložiť' })).toBeEnabled();
  });
});
