import type { Metadata, Viewport } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Thrive — Save smarter. Eat better.",
  description: "Family savings, affordable recipes, pantry planning, and grocery budgeting in one simple app.",
  openGraph: {
    title: "Thrive — Save smarter. Eat better.",
    description: "Save on groceries, plan affordable meals, and stay on budget.",
    images: [{ url: "/og-mobile.png", width: 1200, height: 630, alt: "Thrive mobile grocery savings app" }],
  },
  twitter: { card: "summary_large_image", images: ["/og-mobile.png"] },
  appleWebApp: { capable: true, statusBarStyle: "default", title: "Thrive" },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  maximumScale: 1,
  viewportFit: "cover",
  themeColor: "#F7F8F6",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}
