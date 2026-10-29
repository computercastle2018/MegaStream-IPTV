import { useEffect, useState } from "react";
import type { ContentItem, ContentType, Playable, Provider } from "./types";
import {
  getActiveProviderId,
  loadProviders,
  setActiveProviderId as persistActiveId,
} from "./services/storage";
import ProviderSetupScreen from "./screens/ProviderSetupScreen";
import HomeScreen from "./screens/HomeScreen";
import ContentScreen, { initialBrowseState, type BrowseState } from "./screens/ContentScreen";
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
  const [browseViews, setBrowseViews] = useState<Record<string, BrowseState>>({});

  const activeProvider = providers.find((p) => p.id === activeId) ?? providers[0] ?? null;
  useEffect(() => {
    const sync = () => {
      const next = loadProviders();
      setProviders(next);
      setActiveId(getActiveProviderId());
      setRoute(current => current === "setup" && next.length > 0 ? "home" : current);
    };
    window.addEventListener("megastream-provider-sync", sync);
    return () => window.removeEventListener("megastream-provider-sync", sync);
  }, []);

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
  const manageProviders = () => {
    if (!window.megastreamDesktop) { setRoute("setup"); return; }
    window.megastreamDesktop.control.state().then(state => {
      if (state.allowSubscriptionDetails) setRoute("setup");
      else window.dispatchEvent(new Event("megastream-license-open"));
    });
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
      fallbackStreamUrl: item.fallbackStreamUrl,
      streamFormat: item.streamFormat,
    });
    setPlayerReturn("browse");
    setRoute("player");
  };

  if (route === "player" && playable) {
    return <PlayerScreen channel={playable} onBack={() => setRoute(playerReturn)} onHome={() => setRoute("home")} />;
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
        onHome={() => setRoute("home")}
      />
    );
  }

  if (route === "browse" && activeProvider) {
    return (
      <ContentScreen
        key={`${activeProvider.id}:${contentType}`}
        provider={activeProvider}
        providers={providers}
        contentType={contentType}
        onChangeType={setContentType}
        onSelectProvider={selectActive}
        onSelect={playItem}
        onManageProviders={() => setRoute("home")}
        viewState={browseViews[`${activeProvider.id}:${contentType}`] ?? initialBrowseState}
        onViewStateChange={next => setBrowseViews(previous => ({ ...previous, [`${activeProvider.id}:${contentType}`]: next }))}
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
        onChangePlaylist={manageProviders}
        onSettings={manageProviders}
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
