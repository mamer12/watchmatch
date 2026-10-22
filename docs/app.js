// WatchMatch site: remembers the language, reveals sections, keeps the nav tidy, pauses videos off screen.
(function () {
  var doc = document, root = doc.documentElement;
  var each = function (sel, fn) { Array.prototype.forEach.call(doc.querySelectorAll(sel), fn); };
  var calm = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
  var seen = "IntersectionObserver" in window;

  // Language switch: a click is the visitor's choice; the current page also counts as chosen.
  try {
    each(".langs a[data-lang]", function (a) {
      a.addEventListener("click", function () { localStorage.setItem("lang", a.getAttribute("data-lang")); });
    });
  } catch (e) {}

  // The nav gets a border once the page scrolls.
  var nav = doc.getElementById("nav");
  if (nav) {
    var mark = function () { nav.classList.toggle("scrolled", window.scrollY > 8); };
    window.addEventListener("scroll", mark, { passive: true });
    mark();
  }

  // Sections fade in once. Without observer support (or with reduced motion) they just show.
  if (seen && !calm) {
    root.classList.add("js");
    var reveal = new IntersectionObserver(function (items) {
      items.forEach(function (it) {
        if (it.isIntersecting) { it.target.classList.add("in"); reveal.unobserve(it.target); }
      });
    }, { threshold: 0.12 });
    each("[data-reveal]", function (el, i) { el.style.setProperty("--d", (i % 4) * 0.06 + "s"); reveal.observe(el); });
  }

  // Videos play only while visible.
  if (seen) {
    var player = new IntersectionObserver(function (items) {
      items.forEach(function (it) {
        var v = it.target;
        if (it.isIntersecting && !calm) { var p = v.play(); if (p && p.catch) p.catch(function () {}); }
        else v.pause();
      });
    }, { threshold: 0.25 });
    each("video", function (v) { v.removeAttribute("autoplay"); v.pause(); player.observe(v); });
  }

  // FAQ: opening one answer closes the others.
  each("#faq details", function (d) {
    d.addEventListener("toggle", function () {
      if (!d.open) return;
      each("#faq details", function (o) { if (o !== d) o.open = false; });
    });
  });
})();
