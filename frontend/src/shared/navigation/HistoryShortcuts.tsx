import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';

/** Alt+← / Alt+→ a bočné tlačidlá myši (Back/Forward) ovládajú históriu aplikácie v okne Tauri aj v prehliadači. */
export function HistoryShortcuts() {
  const navigate = useNavigate();
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return;
      if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
      const t = e.target as HTMLElement | null;
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
      e.preventDefault();
      void navigate(e.key === 'ArrowLeft' ? -1 : 1);
    };
    const onMouse = (e: MouseEvent) => {
      if (e.button !== 3 && e.button !== 4) return;
      e.preventDefault();
      void navigate(e.button === 3 ? -1 : 1);
    };
    window.addEventListener('keydown', onKey);
    window.addEventListener('mouseup', onMouse);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('mouseup', onMouse);
    };
  }, [navigate]);
  return null;
}
