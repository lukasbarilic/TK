import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import sk from './sk.json';

void i18n.use(initReactI18next).init({
  lng: 'sk',
  fallbackLng: 'sk',
  resources: { sk: { translation: sk } },
  interpolation: { escapeValue: false },
});

export default i18n;
