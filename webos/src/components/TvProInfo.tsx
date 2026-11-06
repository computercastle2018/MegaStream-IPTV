import type { ContentItem } from "../types";
import ContentImage from "./ContentImage";

export default function TvProInfo({ item }: { item: ContentItem }) {
  return <aside className="tvpro-info">
    <div className="tvpro-info-poster"><ContentImage src={item.imageUrl} name={item.name} /></div>
    <h2 dir="auto">{item.name}</h2>
    <p dir="auto">{item.groupTitle}</p>
  </aside>;
}
