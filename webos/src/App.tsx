import { useEffect, useState } from "react";
import type { ContentItem, ContentType, Playable, Provider } from "./types";
import {
  getActiveProviderId,
  loadProviders,
  setActiveProviderId as persistActiveId,
} from "./services/storage";
import ProviderSetupScreen from "./screens/ProviderSetupScreen";
import HomeScreen from "./screens/HomeScreen";
import ContentScreen from "./screens/ContentScreen";
import EpisodesScreen from "./screens/EpisodesScreen";
import PlayerScreen from "./screens/PlayerScreen";

type Route = "setup" | "home" | "browse" | "episodes" | "player";

export default function App() {
  const [providers, setProviders] = useState<Provider[]>(() => loadProviders());
  const [activeId, setActiveId] = useState<string | null>(() => getActiveProviderId());
  const [route, setRoute] = useState<Route>(() =>
    loadProviders().length > 0 ? "home" : "setup",
  );
  const [contentType, setContentType] = useState<ContentType>("live");
  const [series, setSeries] = useState<ContentItem | null>(null);
  const [playable, setPlayable] = useState<Playable | null>(null);
  const [playerReturn, setPlayerReturn] = useState<Route>("browse");

  const activeProvider = providers.find((p) => p.id === activeId) ?? providers[0] ?? null;

  useEffect(() => {
    if ((route === "browse" || route === "home") && !activeProvider) setRoute("setup");
  }, [route, activeProvider]);

  const handleProvidersChanged = (next: Provider[]) => {
    setProviders(next);
    setActiveId(getActiveProviderId());
  };

  const selectActive = (id: string) => {
    persistActiveId(id);
    setActiveId(id);
  };

  const playItem = (item: ContentItem) => {
    if (item.kind === "series" && item.seriesId) {
      setSeries(item);
      setRoute("episodes");
      return;
    }
    if (!item.streamUrl) return;
    setPlayable({
      name: item.name,
      number: item.number,
      groupTitle: item.groupTitle,
      streamUrl: item.streamUrl,
      streamFormat: item.streamFormat,
    });
    setPlayerReturn("browse");
    setRoute("player");
  };

  if (route === "player" && playable) {
    return <PlayerScreen channel={playable} onBack={() => setRoute(playerReturn)} />;
  }

  if (route === "episodes" && series && activeProvider) {
    return (
      <EpisodesScreen
        provider={activeProvider}
        series={series}
        onPlay={(ep) => {
          setPlayable(ep);
          setPlayerReturn("episodes");
          setRoute("player");
        }}
        onBack={() => setRoute("browse")}
      />
    );
  }

  if (route === "browse" && activeProvider) {
    return (
      <ContentScreen
        provider={activeProvider}
        providers={providers}
        contentType={contentType}
        onChangeType={setContentType}
        onSelectProvider={selectActive}
        onSelect={playItem}
        onManageProviders={() => setRoute("home")}
      />
    );
  }

  if (route === "home" && activeProvider) {
    return (
      <HomeScreen
        provider={activeProvider}
        onOpenType={(type) => {
          setContentType(type);
          setRoute("browse");
        }}
        onChangePlaylist={() => setRoute("setup")}
        onSettings={() => setRoute("setup")}
      />
    );
  }

  return (
    <ProviderSetupScreen
      providers={providers}
      onChange={handleProvidersChanged}
      onConnect={(id) => {
        selectActive(id);
        setRoute("home");
      }}
      onDone={() => setRoute("home")}
      canGoBack={providers.length > 0}
    />
  );
}
