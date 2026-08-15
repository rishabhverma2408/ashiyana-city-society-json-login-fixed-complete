package com.society.management.config;
import jakarta.servlet.*; import jakarta.servlet.http.*; import org.springframework.stereotype.Component; import org.springframework.web.filter.OncePerRequestFilter; import java.io.*;
@Component public class AuthFilter extends OncePerRequestFilter{
 protected void doFilterInternal(HttpServletRequest q,HttpServletResponse s,FilterChain c)throws ServletException,IOException{
  String p=q.getRequestURI();
  if(p.equals("/")||p.equals("/index.html")||p.startsWith("/h2-console")||p.startsWith("/api/auth")){c.doFilter(q,s);return;}
  if(p.startsWith("/api/")&&q.getSession(false)!=null&&q.getSession(false).getAttribute("role")!=null){c.doFilter(q,s);return;}
  if(p.startsWith("/api/")){s.setStatus(401);s.setContentType("application/json");s.getWriter().write("{\"error\":\"Authentication required\"}");return;}
  c.doFilter(q,s);
 }}